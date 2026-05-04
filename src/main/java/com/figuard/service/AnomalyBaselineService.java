package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.BudgetAnomalyBaseline;
import com.figuard.domain.repository.BudgetAnomalyBaselineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Maintains a per-budget rolling baseline of confirmed spend amounts.
 *
 * Uses Welford's online algorithm to update the running mean and variance
 * without loading historical events. Called asynchronously after every
 * CONFIRMED event so the authorize path is never blocked.
 *
 * The anomaly check (in AuthorizationService) reads meanAmount from this
 * baseline and compares requestedAmount > meanAmount * multiplier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyBaselineService {

    private static final MathContext MC = new MathContext(19, RoundingMode.HALF_UP);

    private final BudgetAnomalyBaselineRepository baselineRepository;

    /**
     * Update the baseline for the given budget with a new confirmed amount.
     * Creates the baseline row on first call; subsequent calls update in-place.
     * Runs on the webhookExecutor thread pool so confirm is not blocked.
     */
    @Async("webhookExecutor")
    @Transactional
    public void updateBaseline(AgentBudget budget, BigDecimal confirmedAmount) {
        if (!budget.isAnomalyDetectionEnabled()) {
            return;
        }

        // Lock the baseline row to prevent concurrent Welford updates from diverging
        BudgetAnomalyBaseline baseline = baselineRepository
            .findByBudgetIdWithLock(budget.getId())
            .orElseGet(() -> {
                BudgetAnomalyBaseline b = new BudgetAnomalyBaseline();
                b.setBudget(budget);
                b.setTenant(budget.getTenant());
                return b;
            });

        int n = baseline.getSampleCount() + 1;
        BigDecimal newN = BigDecimal.valueOf(n);

        // Welford's: delta = x - mean_old; mean_new = mean_old + delta / n
        BigDecimal meanOld = baseline.getMeanAmount() != null
            ? baseline.getMeanAmount() : BigDecimal.ZERO;
        BigDecimal delta = confirmedAmount.subtract(meanOld);
        BigDecimal meanNew = meanOld.add(delta.divide(newN, MC));

        // Welford's M2 for std dev (M2 not stored — we derive std dev at update time)
        // std_dev = sqrt(M2 / (n-1)); we approximate using running variance
        BigDecimal stdDev = BigDecimal.ZERO;
        if (n >= 2) {
            BigDecimal delta2 = confirmedAmount.subtract(meanNew);
            BigDecimal prevM2 = computeM2(baseline, meanOld, n - 1);
            BigDecimal newM2 = prevM2.add(delta.multiply(delta2, MC));
            stdDev = sqrt(newM2.divide(BigDecimal.valueOf(n - 1), MC));
        }

        baseline.setSampleCount(n);
        baseline.setMeanAmount(meanNew.setScale(4, RoundingMode.HALF_UP));
        baseline.setStdDevAmount(stdDev.setScale(4, RoundingMode.HALF_UP));
        baseline.setMinAmount(baseline.getMinAmount() == null
            ? confirmedAmount
            : baseline.getMinAmount().min(confirmedAmount));
        baseline.setMaxAmount(baseline.getMaxAmount() == null
            ? confirmedAmount
            : baseline.getMaxAmount().max(confirmedAmount));

        baselineRepository.save(baseline);

        log.debug("Baseline updated: budgetId={} n={} mean={} stdDev={}",
            budget.getId(), n, meanNew, stdDev);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Back-compute M2 from the stored std_dev and n.
     * M2 = variance * (n - 1) = stdDev^2 * (n - 1)
     */
    private BigDecimal computeM2(BudgetAnomalyBaseline baseline, BigDecimal meanOld, int prevN) {
        if (prevN <= 1 || baseline.getStdDevAmount() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal s = baseline.getStdDevAmount();
        return s.multiply(s, MC).multiply(BigDecimal.valueOf(prevN - 1), MC);
    }

    /** Newton-Raphson square root for BigDecimal. */
    private BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal x = BigDecimal.valueOf(Math.sqrt(value.doubleValue()));
        // One Newton-Raphson refinement step for higher precision
        x = x.add(value.divide(x, MC)).divide(BigDecimal.TWO, MC);
        return x;
    }
}
