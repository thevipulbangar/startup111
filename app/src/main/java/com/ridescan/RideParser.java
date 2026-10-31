package com.ridescan;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure text logic: which app, ride started/ended, fare. No Android imports so it can be tested with plain java. */
public final class RideParser {

    // ponytail: keyword match on notification text. Real wording varies by app/version/language;
    // check the OTHER rows in the app and add the exact phrases you see here.
    static final String[] END = {
            "ride completed", "trip completed", "ride ended", "trip ended", "ride is over", "ride over",
            "trip is over", "ride has ended", "trip has ended", "ride has been ended", "ride is ended", "ride has been completed", "trip has been completed",
            "collect cash", "collect payment", "drop completed", "you have reached the drop"
    };
    static final String[] START = {
            "ride started", "trip started", "ride has started", "ride is started", "ride has been started", "trip has started", "started the ride",
            "started the trip", "ride is on", "otp verified", "heading to drop", "on the way to drop"
    };
    /** Every app name appName() can return; drives the on/off switches in the app. */
    public static final String[] APPS = {"Ola", "Uber", "Rapido", "WhatsApp"};

    private static final Pattern FARE = Pattern.compile("(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");

    /** Returns "Ola", "Uber", "Rapido", "WhatsApp", or null if the package is not a ride app. */
    public static String appName(String pkg) {
        if (pkg == null) return null;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("olacabs") || p.contains("oladriver")) return "Ola";
        if (p.contains("ubercab") || p.startsWith("com.uber")) return "Uber";
        if (p.contains("rapido")) return "Rapido";
        if (p.startsWith("com.whatsapp")) return "WhatsApp";
        return null;
    }

    // Test mode: WhatsApp messages from this contact act like a driver app ("Ride is started" /
    // "Ride has been ended"). WhatsApp messages from anyone else never start or stop a ride.
    static final String WHATSAPP_TEST_SENDER = "chituuuuuu";

    /** Like classify(text), but WhatsApp only counts when it comes from the test sender. */
    public static String classify(String app, String title, String text) {
        if ("WhatsApp".equals(app) && !title.toLowerCase(Locale.ROOT).contains(WHATSAPP_TEST_SENDER)) return "OTHER";
        return classify(text);
    }

    /** Returns "END", "START" or "OTHER". END is checked first so "trip completed" never reads as a start. */
    public static String classify(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : END) if (t.contains(k)) return "END";
        for (String k : START) if (t.contains(k)) return "START";
        return "OTHER";
    }

    /** First rupee amount in the text, e.g. "245.50", or "" if none. */
    public static String fare(String text) {
        Matcher m = FARE.matcher(text.toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1).replace(",", "") : "";
    }

    /** Self-check: java app/src/main/java/com/ridescan/RideParser.java */
    public static void main(String[] a) {
        check("Ola".equals(appName("com.olacabs.oladriver")));
        check("Uber".equals(appName("com.ubercab.driver")));
        check("Rapido".equals(appName("com.rapido.captain")));
        check(appName("com.motorola.launcher") == null);
        check("WhatsApp".equals(appName("com.whatsapp")));
        check("START".equals(classify("Your trip has started. Drive safe")));
        check("END".equals(classify("Trip completed! Collect ₹245")));
        check("END".equals(classify("Ride Started earlier — ride completed now")));
        check("OTHER".equals(classify("New ride request nearby")));
        check("START".equals(classify("WhatsApp", "Chituuuuuu", "Chituuuuuu Ride is started")));
        check("END".equals(classify("WhatsApp", "Chituuuuuu", "Chituuuuuu Ride has been ended")));
        check("OTHER".equals(classify("WhatsApp", "Aryaan Antarkar", "Aryaan Antarkar Ride is started")));
        check("START".equals(classify("Rapido", "Ride update", "Ride is started")));
        check("1245.50".equals(fare("Collect Rs. 1,245.50 from rider")));
        check("".equals(fare("no money here")));
        System.out.println("RideParser OK");
    }

    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
