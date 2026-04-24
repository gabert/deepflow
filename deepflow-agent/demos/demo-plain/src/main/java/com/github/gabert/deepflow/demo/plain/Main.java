package com.github.gabert.deepflow.demo.plain;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Greeter greeter = new Greeter();
        String result = greeter.greet("World");
        System.out.println("Result: " + result);

        List<String> items = new ArrayList<>();
        items.add("original");
        greeter.sneakyMutate(items);
        System.out.println("Items after: " + items);
    }
}
