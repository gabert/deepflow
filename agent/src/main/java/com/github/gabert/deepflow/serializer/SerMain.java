package com.github.gabert.deepflow.serializer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

public class SerMain {
    public static void main(String[] args) {
        Gson GSON_DATA = new GsonBuilder()
                .registerTypeAdapterFactory(new MetaIdTypeAdapterFactory())
                .create();

        String json = GSON_DATA.toJson(new Person(
                "John",
                "London",
                "Paris",
                "New York"));

        System.out.println(json);
    }
    
    public static class Person {
        private final String name;
        private final Address privateAddress;
        private final List<Address> businessAddresses;

        public Person(String name, String privateAddress, String businessAddress1, String businessAddress2) {
            this.name = name;
            this.privateAddress = new Address(privateAddress);
            this.businessAddresses = new ArrayList<>();
            this.businessAddresses.add(new Address(businessAddress1));
            this.businessAddresses.add(new Address(businessAddress2));
        }
    }
    
    public static class Address {
        private final String townName;

        public Address(String townName) {
            this.townName = townName;
        }
    }
}
