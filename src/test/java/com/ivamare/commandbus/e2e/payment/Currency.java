package com.ivamare.commandbus.e2e.payment;

/**
 * ISO 4217 currency codes for major OECD currencies.
 */
public enum Currency {
    USD("US Dollar", "United States"),
    EUR("Euro", "European Union"),
    GBP("British Pound", "United Kingdom"),
    JPY("Japanese Yen", "Japan"),
    CHF("Swiss Franc", "Switzerland"),
    CAD("Canadian Dollar", "Canada"),
    AUD("Australian Dollar", "Australia"),
    NZD("New Zealand Dollar", "New Zealand"),
    SEK("Swedish Krona", "Sweden"),
    NOK("Norwegian Krone", "Norway"),
    DKK("Danish Krone", "Denmark"),
    PLN("Polish Zloty", "Poland"),
    CZK("Czech Koruna", "Czech Republic"),
    HUF("Hungarian Forint", "Hungary"),
    MXN("Mexican Peso", "Mexico"),
    KRW("South Korean Won", "South Korea"),
    SGD("Singapore Dollar", "Singapore"),
    HKD("Hong Kong Dollar", "Hong Kong"),
    INR("Indian Rupee", "India"),
    BRL("Brazilian Real", "Brazil"),
    ZAR("South African Rand", "South Africa"),
    TRY("Turkish Lira", "Turkey"),
    ILS("Israeli Shekel", "Israel"),
    THB("Thai Baht", "Thailand");

    private final String name;
    private final String country;

    Currency(String name, String country) {
        this.name = name;
        this.country = country;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }
}
