package bench;

import java.util.List;

public class Order {
    private final long id;
    private final String customer;
    private final List<String> items;
    private final double total;

    public Order(long id, String customer, List<String> items, double total) {
        this.id = id;
        this.customer = customer;
        this.items = items;
        this.total = total;
    }

    public long getId() { return id; }
    public String getCustomer() { return customer; }
    public List<String> getItems() { return items; }
    public double getTotal() { return total; }
}
