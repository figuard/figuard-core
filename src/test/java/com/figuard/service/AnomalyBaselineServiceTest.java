package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAnomalyBaseline;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.repository.BudgetAnomalyBaselineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnomalyBaselineServiceTest {

    @Mock BudgetAnomalyBaselineRepository baselineRepository;

    @InjectMocks AnomalyBaselineService service;

    AgentBudget budget;

    @BeforeEach
    void setUp() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        budget = new AgentBudget();
        budget.setId(UUID.randomUUID());
        budget.setTenant(tenant);
        budget.setAnomalyDetectionEnabled(true);
    }

    // -------------------------------------------------------------------------
    // Disabled — no-op
    // -------------------------------------------------------------------------

    @Test
    void updateBaseline_skips_whenAnomalyDetectionDisabled() {
        budget.setAnomalyDetectionEnabled(false);

        service.updateBaseline(budget, new BigDecimal("100.00"));

        verifyNoInteractions(baselineRepository);
    }

    // -------------------------------------------------------------------------
    // First sample (n=1)
    // -------------------------------------------------------------------------

    @Test
    void updateBaseline_createsBaseline_onFirstSample() {
        when(baselineRepository.findByBudgetIdWithLock(budget.getId()))
            .thenReturn(Optional.empty());
        when(baselineRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.updateBaseline(budget, new BigDecimal("100.00"));

        ArgumentCaptor<BudgetAnomalyBaseline> captor =
            ArgumentCaptor.forClass(BudgetAnomalyBaseline.class);
        verify(baselineRepository).save(captor.capture());

        BudgetAnomalyBaseline saved = captor.getValue();
        assertThat(saved.getSampleCount()).isEqualTo(1);
        assertThat(saved.getMeanAmount()).isEqualByComparingTo("100.00");
        // stdDev is zero for a single sample — cannot compute variance with n<2
        assertThat(saved.getStdDevAmount()).isEqualByComparingTo("0.00");
        assertThat(saved.getMinAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getMaxAmount()).isEqualByComparingTo("100.00");
    }

    // -------------------------------------------------------------------------
    // Mean convergence — Welford's algorithm correctness
    // -------------------------------------------------------------------------

    @Test
    void updateBaseline_mean_convergesCorrectly_afterTwoSamples() {
        // First call: baseline starts fresh (n=0)
        BudgetAnomalyBaseline baseline = emptyBaseline();
        when(baselineRepository.findByBudgetIdWithLock(budget.getId()))
            .thenReturn(Optional.of(baseline));
        when(baselineRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // First sample: 100.00 → mean = 100.00
        applyWelford(baseline, new BigDecimal("100.00"));
        service.updateBaseline(budget, new BigDecimal("100.00"));

        ArgumentCaptor<BudgetAnomalyBaseline> cap1 =
            ArgumentCaptor.forClass(BudgetAnomalyBaseline.class);
        verify(baselineRepository, times(1)).save(cap1.capture());
        BudgetAnomalyBaseline after1 = cap1.getValue();
        assertThat(after1.getMeanAmount()).isEqualByComparingTo("100.0000");
        assertThat(after1.getSampleCount()).isEqualTo(1);

        // Second sample: 200.00 → mean = (100+200)/2 = 150.00
        baseline.setSampleCount(1);
        baseline.setMeanAmount(new BigDecimal("100.0000"));
        baseline.setStdDevAmount(BigDecimal.ZERO);

        service.updateBaseline(budget, new BigDecimal("200.00"));

        ArgumentCaptor<BudgetAnomalyBaseline> cap2 =
            ArgumentCaptor.forClass(BudgetAnomalyBaseline.class);
        verify(baselineRepository, times(2)).save(cap2.capture());
        BudgetAnomalyBaseline after2 = cap2.getValue();
        assertThat(after2.getMeanAmount()).isEqualByComparingTo("150.0000");
        assertThat(after2.getSampleCount()).isEqualTo(2);
        // sample std dev for [100, 200]: sqrt(((100-150)^2 + (200-150)^2) / (2-1)) = sqrt(5000) ≈ 70.7107
        assertThat(after2.getStdDevAmount()).isEqualByComparingTo("70.7107");
    }

    @Test
    void updateBaseline_mean_correctWith_threeUniformSamples() {
        // Three samples all 60.00 → mean stays 60.00, stdDev = 0
        BudgetAnomalyBaseline baseline = emptyBaseline();
        when(baselineRepository.findByBudgetIdWithLock(budget.getId()))
            .thenReturn(Optional.of(baseline));
        when(baselineRepository.save(any())).thenAnswer(i -> {
            // Update the baseline object in place so the next call sees updated state
            BudgetAnomalyBaseline b = i.getArgument(0);
            baseline.setSampleCount(b.getSampleCount());
            baseline.setMeanAmount(b.getMeanAmount());
            baseline.setStdDevAmount(b.getStdDevAmount());
            baseline.setMinAmount(b.getMinAmount());
            baseline.setMaxAmount(b.getMaxAmount());
            return b;
        });

        service.updateBaseline(budget, new BigDecimal("60.00"));
        service.updateBaseline(budget, new BigDecimal("60.00"));
        service.updateBaseline(budget, new BigDecimal("60.00"));

        ArgumentCaptor<BudgetAnomalyBaseline> cap =
            ArgumentCaptor.forClass(BudgetAnomalyBaseline.class);
        verify(baselineRepository, times(3)).save(cap.capture());

        BudgetAnomalyBaseline finalState = cap.getValue();
        assertThat(finalState.getSampleCount()).isEqualTo(3);
        assertThat(finalState.getMeanAmount()).isEqualByComparingTo("60.0000");
        assertThat(finalState.getStdDevAmount()).isEqualByComparingTo("0.0000");
    }

    // -------------------------------------------------------------------------
    // Min / Max tracking
    // -------------------------------------------------------------------------

    @Test
    void updateBaseline_tracksMinAndMax() {
        BudgetAnomalyBaseline baseline = emptyBaseline();
        when(baselineRepository.findByBudgetIdWithLock(budget.getId()))
            .thenReturn(Optional.of(baseline));
        when(baselineRepository.save(any())).thenAnswer(i -> {
            BudgetAnomalyBaseline b = i.getArgument(0);
            baseline.setSampleCount(b.getSampleCount());
            baseline.setMeanAmount(b.getMeanAmount());
            baseline.setStdDevAmount(b.getStdDevAmount());
            baseline.setMinAmount(b.getMinAmount());
            baseline.setMaxAmount(b.getMaxAmount());
            return b;
        });

        service.updateBaseline(budget, new BigDecimal("50.00"));
        service.updateBaseline(budget, new BigDecimal("200.00"));
        service.updateBaseline(budget, new BigDecimal("10.00"));

        ArgumentCaptor<BudgetAnomalyBaseline> cap =
            ArgumentCaptor.forClass(BudgetAnomalyBaseline.class);
        verify(baselineRepository, times(3)).save(cap.capture());
        BudgetAnomalyBaseline finalState = cap.getValue();

        assertThat(finalState.getMinAmount()).isEqualByComparingTo("10.00");
        assertThat(finalState.getMaxAmount()).isEqualByComparingTo("200.00");
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void updateBaseline_handlesZeroAmount() {
        // Zero-value confirmations are unusual but should not crash
        BudgetAnomalyBaseline baseline = emptyBaseline();
        when(baselineRepository.findByBudgetIdWithLock(budget.getId()))
            .thenReturn(Optional.of(baseline));
        when(baselineRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.updateBaseline(budget, BigDecimal.ZERO);

        ArgumentCaptor<BudgetAnomalyBaseline> cap =
            ArgumentCaptor.forClass(BudgetAnomalyBaseline.class);
        verify(baselineRepository).save(cap.capture());
        assertThat(cap.getValue().getMeanAmount()).isEqualByComparingTo("0.0000");
        assertThat(cap.getValue().getStdDevAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    void updateBaseline_existingBaseline_isLockedBeforeUpdate() {
        // Verifies we use the locking query (findByBudgetIdWithLock), not findByBudgetId
        when(baselineRepository.findByBudgetIdWithLock(budget.getId()))
            .thenReturn(Optional.empty());
        when(baselineRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.updateBaseline(budget, new BigDecimal("75.00"));

        verify(baselineRepository).findByBudgetIdWithLock(budget.getId());
        verify(baselineRepository, never()).findByBudgetId(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BudgetAnomalyBaseline emptyBaseline() {
        BudgetAnomalyBaseline b = new BudgetAnomalyBaseline();
        b.setBudget(budget);
        b.setSampleCount(0);
        return b;
    }

    // Simulates what service does so we can pre-warm baseline for chained tests
    private void applyWelford(BudgetAnomalyBaseline b, BigDecimal amount) {
        // used only conceptually — actual math is tested by calling service twice
    }
}
