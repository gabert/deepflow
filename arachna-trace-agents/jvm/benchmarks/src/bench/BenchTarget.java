package bench;

/**
 * Instrumented workload: three nested traced calls per process() invocation,
 * with a POJO + primitives as arguments and non-void returns.
 */
public class BenchTarget {

    public double process(Order order, int quantity) {
        boolean ok = validate(order, quantity);
        if (!ok) return -1.0;
        return price(order, quantity, "EUR");
    }

    public boolean validate(Order order, int quantity) {
        return order != null && quantity > 0 && order.getTotal() >= 0.0;
    }

    public double price(Order order, int quantity, String currency) {
        double base = order.getTotal() * quantity;
        if ("EUR".equals(currency)) return base;
        return base * 1.1;
    }
}
