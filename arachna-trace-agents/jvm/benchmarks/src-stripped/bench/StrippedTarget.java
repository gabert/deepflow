package bench;

/**
 * Same shape as BenchTarget but compiled with -g:none (no LocalVariableTable,
 * no MethodParameters) to exercise the parameter-name resolution fallback path.
 */
public class StrippedTarget {

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
