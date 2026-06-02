package com.ecommerce.order.service;

import com.ecommerce.order.domain.entity.Order;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Order aggregate state machine.
 * Pure JUnit 5.12 – no Mockito needed (no external dependencies on Order).
 * SLF4J is tested implicitly; Order entity does not log directly.
 */
@DisplayName("Order aggregate – state machine unit tests")
class OrderStateMachineTest {

    private Order buildOrder(Order.OrderStatus status) {
        Order o = Order.builder()
            .id(UUID.randomUUID()).userId(UUID.randomUUID())
            .totalAmount(BigDecimal.TEN).sagaId(UUID.randomUUID())
            .build();
        // Force status via reflection-free builder trick
        switch (status) {
            case CONFIRMED -> o.confirm();
            case SHIPPED   -> { o.confirm(); o.ship(); }
            case DELIVERED -> { o.confirm(); o.ship(); o.deliver(); }
            default        -> {} // PENDING is default
        }
        return o;
    }

    @Nested
    @DisplayName("confirm()")
    class ConfirmTests {

        @Test
        @DisplayName("PENDING → CONFIRMED is a valid transition")
        void shouldTransitionFromPendingToConfirmed() {
            Order order = buildOrder(Order.OrderStatus.PENDING);
            order.confirm();
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        }

        @Test
        @DisplayName("confirm() throws when status is not PENDING")
        void shouldThrow_whenNotPending() {
            Order order = buildOrder(Order.OrderStatus.CONFIRMED);
            assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("ship()")
    class ShipTests {

        @Test
        @DisplayName("CONFIRMED → SHIPPED is a valid transition")
        void shouldTransitionFromConfirmedToShipped() {
            Order order = buildOrder(Order.OrderStatus.CONFIRMED);
            order.ship();
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("ship() throws when status is not CONFIRMED")
        void shouldThrow_whenNotConfirmed() {
            Order order = buildOrder(Order.OrderStatus.PENDING);
            assertThatThrownBy(order::ship).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("deliver()")
    class DeliverTests {

        @Test
        @DisplayName("SHIPPED → DELIVERED is a valid transition")
        void shouldTransitionFromShippedToDelivered() {
            Order order = buildOrder(Order.OrderStatus.SHIPPED);
            order.deliver();
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.DELIVERED);
        }
    }

    @Nested
    @DisplayName("cancel()")
    class CancelTests {

        @ParameterizedTest
        @EnumSource(value = Order.OrderStatus.class,
                    names = {"PENDING", "CONFIRMED", "SHIPPED"})
        @DisplayName("cancel() succeeds from non-terminal states")
        void shouldCancelFromNonTerminalState(Order.OrderStatus status) {
            Order order = buildOrder(status);
            order.cancel("test reason");
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancel() throws IllegalStateException when DELIVERED")
        void shouldThrow_whenDelivered() {
            Order order = buildOrder(Order.OrderStatus.DELIVERED);
            assertThatThrownBy(() -> order.cancel("too late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivered");
        }
    }

    @Nested
    @DisplayName("addItem()")
    class AddItemTests {

        @Test
        @DisplayName("addItem() establishes bidirectional relationship")
        void shouldEstablishBidirectionalRelationship() {
            Order order = buildOrder(Order.OrderStatus.PENDING);
            var item = com.ecommerce.order.domain.entity.OrderItem.builder()
                .productId(UUID.randomUUID()).sku("SKU-001")
                .productName("Test").quantity(2)
                .unitPrice(BigDecimal.TEN)
                .lineTotal(new BigDecimal("20")).build();

            order.addItem(item);

            assertThat(order.getItems()).hasSize(1);
            assertThat(item.getOrder()).isSameAs(order);
        }
    }
}
