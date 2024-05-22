package com.github.gabert.deepflow.demo;

import java.util.Objects;

public class Main {
    public static void main(String[] args) {
        String name = "John";
        System.out.println(sayHelloTo(name));
        printAgeToConsole(name, calculateAge(2024,1986));

        Person person = createPerson(name, "London");

        printPerson(person);

        person.address.town = "Paris";

        printPerson(person);

        Person clonedPerson = person.clone();

        printPerson(clonedPerson);
    }

    private static String sayHelloTo(String name) {
        return "Hello " + name  + "!";
    }

    private static int calculateAge(int thisYear, int yearOfBirth) {
        return thisYear - yearOfBirth;
    }

    private static void printAgeToConsole(String name, int age) {
        System.out.println(name + " is " + age + " years old.");
    }

    private static Person createPerson(String name, String town) {
        Person person = new Person();
        person.name = name;
        person.address = createAddress(town);

        return person;
    }

    private static Address createAddress(String town) {
        Address address = new Address();
        address.town = town;

        return address;
    }

    private static void printPerson(Person person) {
        System.out.println(person);
    }

    private static class Person implements Cloneable {
        private String name;
        private Address address;

        @Override
        public int hashCode() {
            return Objects.hash(name, address);
        }

        @Override
        public String toString() {
            return "Person{" +
                    "name='" + name + '\'' +
                    ", address=" + address +
                    '}';
        }

        @Override
        public Person clone() {
            try {
                return (Person) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError();
            }
        }
    }

    private static class Address {
        private String town;

        @Override
        public int hashCode() {
            return Objects.hashCode(town);
        }

        @Override
        public String toString() {
            return "Address{" +
                    "town='" + town + '\'' +
                    '}';
        }
    }
}