package com.figuard.service;

import com.figuard.domain.entity.EntitlementItem;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.Subscription;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.DenialCode;
import com.figuard.domain.enums.EntitlementState;
import com.figuard.domain.enums.OveragePolicy;
import com.figuard.domain.repository.EntitlementItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class EntitlementEnforcementServiceTest {

    @Mock EntitlementItemRepository entitlementItemRepository;
    @Mock EntitlementItemService entitlementItemService;

    @InjectMocks EntitlementEnforcementService service;

    private EntitlementItem item;

    @BeforeEach
    void setUp() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("test-tenant");

        Subscription sub = new Subscription();
        sub.setId(UUID.randomUUID());
        sub.setTenant(tenant);

        item = new EntitlementItem();
        item.setId(UUID.randomUUID());
        item.setSubscription(sub);
        item.setName("Monthly API Calls");
        item.setLimitUnit("api_calls");
        item.setLimitQuantity(new BigDecimal("1000"));
        item.setCurrentPeriodConsumed(BigDecimal.ZERO);
        item.setWarnAtPercentage(80);
        item.setOveragePolicy(OveragePolicy.BLOCK);
        item.setState(EntitlementState.NORMAL);
    }

    // -------------------------------------------------------------------------
    // check()
    // -------------------------------------------------------------------------

    @Test
    void check_approved_when_within_limit() {
        EntitlementEnforcementService.CheckResult result = service.check(item, new BigDecimal("500"));
        assertThat(result).isInstanceOf(EntitlementEnforcementService.CheckResult.Approved.class);
        assertThat(result.isDenied()).isFalse();
    }

    @Test
    void check_approved_at_exact_limit() {
        EntitlementEnforcementService.CheckResult result = service.check(item, new BigDecimal("1000"));
        assertThat(result).isInstanceOf(EntitlementEnforcementService.CheckResult.Approved.class);
    }

    @Test
    void check_denied_when_over_limit_block_policy() {
        item.setCurrentPeriodConsumed(new BigDecimal("900"));

        EntitlementEnforcementService.CheckResult result = service.check(item, new BigDecimal("200"));

        assertThat(result).isInstanceOf(EntitlementEnforcementService.CheckResult.Denied.class);
        assertThat(result.isDenied()).isTrue();
        var denied = (EntitlementEnforcementService.CheckResult.Denied) result;
        assertThat(denied.code()).isEqualTo(DenialCode.ENTITLEMENT_LIMIT_REACHED);
        assertThat(denied.message()).contains("100"); // remaining
    }

    @Test
    void check_warn_only_when_over_limit_warn_policy() {
        item.setOveragePolicy(OveragePolicy.WARN_ONLY);
        item.setCurrentPeriodConsumed(new BigDecimal("900"));

        EntitlementEnforcementService.CheckResult result = service.check(item, new BigDecimal("200"));

        assertThat(result).isInstanceOf(EntitlementEnforcementService.CheckResult.WarnOnly.class);
        assertThat(result.isDenied()).isFalse();
    }

    @Test
    void check_denied_when_state_limit_reached_and_still_over() {
        item.setState(EntitlementState.LIMIT_REACHED);
        item.setCurrentPeriodConsumed(new BigDecimal("1000"));

        EntitlementEnforcementService.CheckResult result = service.check(item, new BigDecimal("1"));

        assertThat(result.isDenied()).isTrue();
    }

    @Test
    void check_approved_when_state_limit_reached_but_balance_was_reset() {
        // State is LIMIT_REACHED but currentPeriodConsumed was manually zeroed — allow
        item.setState(EntitlementState.LIMIT_REACHED);
        item.setCurrentPeriodConsumed(BigDecimal.ZERO);

        EntitlementEnforcementService.CheckResult result = service.check(item, new BigDecimal("100"));

        assertThat(result).isInstanceOf(EntitlementEnforcementService.CheckResult.Approved.class);
    }

    // -------------------------------------------------------------------------
    // consume()
    // -------------------------------------------------------------------------

    @Test
    void consume_increments_consumed_and_links_event() {
        SpendEvent event = new SpendEvent();
        when(entitlementItemRepository.save(any())).thenReturn(item);

        service.consume(item, new BigDecimal("300"), event);

        assertThat(item.getCurrentPeriodConsumed()).isEqualByComparingTo("300");
        assertThat(event.getEntitlementItemId()).isEqualTo(item.getId());
        verify(entitlementItemRepository).save(item);
        verify(entitlementItemService).evaluateStateTransition(item);
    }

    // -------------------------------------------------------------------------
    // release()
    // -------------------------------------------------------------------------

    @Test
    void release_decrements_consumed() {
        item.setCurrentPeriodConsumed(new BigDecimal("500"));
        UUID itemId = item.getId();

        when(entitlementItemRepository.findByIdWithLock(itemId)).thenReturn(Optional.of(item));
        when(entitlementItemRepository.save(any())).thenReturn(item);

        service.release(itemId, new BigDecimal("200"));

        assertThat(item.getCurrentPeriodConsumed()).isEqualByComparingTo("300");
        verify(entitlementItemService).evaluateStateTransition(item);
    }

    @Test
    void release_floors_at_zero() {
        item.setCurrentPeriodConsumed(new BigDecimal("50"));
        UUID itemId = item.getId();

        when(entitlementItemRepository.findByIdWithLock(itemId)).thenReturn(Optional.of(item));
        when(entitlementItemRepository.save(any())).thenReturn(item);

        service.release(itemId, new BigDecimal("200"));

        assertThat(item.getCurrentPeriodConsumed()).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // adjust()
    // -------------------------------------------------------------------------

    @Test
    void adjust_releases_delta_on_partial_confirm() {
        item.setCurrentPeriodConsumed(new BigDecimal("500"));
        UUID itemId = item.getId();

        when(entitlementItemRepository.findByIdWithLock(itemId)).thenReturn(Optional.of(item));
        when(entitlementItemRepository.save(any())).thenReturn(item);

        service.adjust(itemId, new BigDecimal("100"), new BigDecimal("80"));

        // delta = 20 released
        assertThat(item.getCurrentPeriodConsumed()).isEqualByComparingTo("480");
    }

    @Test
    void adjust_noop_when_confirmed_equals_reserved() {
        UUID itemId = item.getId();

        service.adjust(itemId, new BigDecimal("100"), new BigDecimal("100"));

        verifyNoInteractions(entitlementItemRepository);
    }

    @Test
    void adjust_noop_when_confirmed_exceeds_reserved() {
        UUID itemId = item.getId();

        // confirmed > reserved — no adjustment (shouldn't happen in practice, but must not error)
        service.adjust(itemId, new BigDecimal("80"), new BigDecimal("100"));

        verifyNoInteractions(entitlementItemRepository);
    }
}
