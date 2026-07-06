package bench;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Enterprise-shaped aggregate: nested customer/address, a dozen line items
 * with BigDecimal amounts and attribute maps, an enum status. Roughly the
 * complexity of a business document flowing through a service layer.
 */
public class Invoice {

    public enum Status { DRAFT, ISSUED, PAID, CANCELLED }

    public static class Address {
        private final String street;
        private final String city;
        private final String zip;
        private final String country;

        public Address(String street, String city, String zip, String country) {
            this.street = street;
            this.city = city;
            this.zip = zip;
            this.country = country;
        }
    }

    public static class Customer {
        private final long id;
        private final String name;
        private final String email;
        private final Address billingAddress;
        private final Address shippingAddress;

        public Customer(long id, String name, String email, Address billing, Address shipping) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.billingAddress = billing;
            this.shippingAddress = shipping;
        }
    }

    public static class LineItem {
        private final String sku;
        private final String description;
        private final int quantity;
        private final BigDecimal unitPrice;
        private final double discountPct;
        private final Map<String, String> attributes;

        public LineItem(String sku, String description, int quantity,
                        BigDecimal unitPrice, double discountPct, Map<String, String> attributes) {
            this.sku = sku;
            this.description = description;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.discountPct = discountPct;
            this.attributes = attributes;
        }

        public BigDecimal total() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    private final long id;
    private final String number;
    private final Status status;
    private final Customer customer;
    private final List<LineItem> items;
    private final Map<String, String> metadata;
    private final BigDecimal totalNet;

    public Invoice(long id, String number, Status status, Customer customer,
                   List<LineItem> items, Map<String, String> metadata, BigDecimal totalNet) {
        this.id = id;
        this.number = number;
        this.status = status;
        this.customer = customer;
        this.items = items;
        this.metadata = metadata;
        this.totalNet = totalNet;
    }

    public long getId() { return id; }
    public Status getStatus() { return status; }
    public List<LineItem> getItems() { return items; }
    public BigDecimal getTotalNet() { return totalNet; }
}
