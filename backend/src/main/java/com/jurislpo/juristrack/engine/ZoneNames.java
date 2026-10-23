package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Names of a time zone at an instant, as the old app showed them: {@link #zoneLongName} ("India Standard Time", "Eastern Daylight
 * Time"), {@link #zoneAbbreviation} ("IST", "EDT") and {@link #zoneLabel} ("India Standard Time (IST)"). Ported from the old app's
 * {@code src/lib/time/zones.ts} ({@code zoneLongName}, {@code zoneAbbr}, {@code zoneLabel}) and checked against golden cases generated
 * from it ({@code golden/time-engine.json}). The CSV export names its time columns with the abbreviation ("Start (EDT)").
 *
 * <p><b>How the old code worked.</b> It asked {@code Intl.DateTimeFormat("en-US")} (ICU) for the short zone name; where English has
 * no abbreviation ICU writes the offset ("GMT+5:30", even "GMT+0" at offset zero), and then the old code took the initials of the long
 * name instead ("India Standard Time" gives "IST", "Nepal Time" gives "NT", Lisbon in winter "WEST").
 *
 * <p><b>Differences between java.time and Node's Intl, and how they are matched here</b> (checked over every zone Node 24 / ICU 78.3
 * lists, January and July 2026):
 * <ul>
 *   <li>Short names: java.time falls back to its own legacy abbreviations where English CLDR has none ("SGT" for Singapore, "NPT" for
 *   Kathmandu), ICU writes the offset instead. So the short name is never taken from java.time: it is the English CLDR abbreviation
 *   where one exists ({@link #ENGLISH_ABBREVIATIONS}, looked up by the long name), else the initials of the long name. Singapore is
 *   therefore "SST" and Kathmandu "NT", as in the old app.</li>
 *   <li>Hawaii: ICU has "HST" / "HDT" only for Honolulu itself; other Hawaii-Aleutian zones (America/Adak) are "HAST" / "HADT".</li>
 *   <li>"British Summer Time" is Europe/London's own name in ICU; Guernsey, Jersey and the Isle of Man get "GMT+01:00" there (so "G"),
 *   and this port does the same.</li>
 *   <li>A zone given as a UTC offset ({@code +05:30}) has no name in java.time ("+05:30"); ICU calls it "GMT+05:30" ("Greenwich Mean
 *   Time" at offset zero).</li>
 *   <li>Not matched (the JDK 17 and Node ship different CLDR versions, so some English names differ): West and Central Africa
 *   (Lagos and its neighbours "West Africa Standard Time" here, "West Africa Time" in Node; Windhoek), Casablanca and El Aaiun,
 *   Kazakhstan, several Russian zones (Astrakhan, Barnaul, Kamchatka, Novosibirsk, Sakhalin, Saratov, Srednekolymsk, Tomsk,
 *   Ulyanovsk, Volgograd, Anadyr), Amman and Damascus, Istanbul, Taipei, Urumqi, Hovd, Brunei, Dili, Famagusta (winter), Greenland,
 *   Yukon, southern Chile (Coyhaique, Punta Arenas, Palmer), Antarctica (Casey, Dumont d'Urville, Troll), Samoa (Apia, Pago Pago,
 *   Midway), Bougainville, Pohnpei and Reunion. India, the Americas' main zones, Europe's main zones, the Gulf, South and East Asia
 *   and Australia agree.</li>
 * </ul>
 */
public final class ZoneNames {

    private static final String BRITISH_SUMMER_TIME = "British Summer Time";

    /**
     * The abbreviations English CLDR (ICU's {@code en-US}) has, keyed by the long name; every other zone gets "GMT+h:mm" from ICU.
     * Metazones Alaska, America_Central, America_Eastern, America_Mountain, America_Pacific, Atlantic, Hawaii_Aleutian and GMT, plus
     * the zone-specific names of Etc/UTC and Europe/London (summer). Honolulu: {@link #HONOLULU_ABBREVIATIONS}.
     */
    private static final Map<String, String> ENGLISH_ABBREVIATIONS = Map.ofEntries(
            Map.entry("Eastern Standard Time", "EST"), Map.entry("Eastern Daylight Time", "EDT"),
            Map.entry("Central Standard Time", "CST"), Map.entry("Central Daylight Time", "CDT"),
            Map.entry("Mountain Standard Time", "MST"), Map.entry("Mountain Daylight Time", "MDT"),
            Map.entry("Pacific Standard Time", "PST"), Map.entry("Pacific Daylight Time", "PDT"),
            Map.entry("Alaska Standard Time", "AKST"), Map.entry("Alaska Daylight Time", "AKDT"),
            Map.entry("Atlantic Standard Time", "AST"), Map.entry("Atlantic Daylight Time", "ADT"),
            Map.entry("Hawaii-Aleutian Standard Time", "HAST"), Map.entry("Hawaii-Aleutian Daylight Time", "HADT"),
            Map.entry("Greenwich Mean Time", "GMT"), Map.entry("Coordinated Universal Time", "UTC"),
            Map.entry(BRITISH_SUMMER_TIME, "BST"));
    /** Honolulu's own abbreviations (CLDR zone-specific names). */
    private static final Map<String, String> HONOLULU_ABBREVIATIONS = Map.of(
            "Hawaii-Aleutian Standard Time", "HST", "Hawaii-Aleutian Daylight Time", "HDT");
    private static final Set<String> HONOLULU_ZONE_IDS = Set.of("Pacific/Honolulu", "Pacific/Johnston", "US/Hawaii");
    private static final Set<String> LONDON_ZONE_IDS = Set.of("Europe/London", "Europe/Belfast", "GB", "GB-Eire");
    private static final DateTimeFormatter LONG_NAME = DateTimeFormatter.ofPattern("zzzz", Locale.US);
    private static final String GREENWICH = "GMT";
    private static final String GREENWICH_MEAN_TIME = "Greenwich Mean Time";

    private ZoneNames() {
    }

    /**
     * The long name at an instant: "India Standard Time", "Eastern Daylight Time"; for a zone given as an offset "GMT+05:30" (and
     * "Greenwich Mean Time" at offset zero), as ICU names it.
     */
    public static String zoneLongName(ZoneId zone, Instant instant) {
        if (zone instanceof ZoneOffset offset) return offsetName(offset);
        String longName = LONG_NAME.format(instant.atZone(zone));
        if (longName.equals(BRITISH_SUMMER_TIME) && !LONDON_ZONE_IDS.contains(zone.getId())) {
            return offsetName(zone.getRules().getOffset(instant));
        }
        return longName;
    }

    /**
     * The short abbreviation at an instant: "IST", "EDT", "EST", "GMT", "AEDT": the English abbreviation where there is one, else the
     * initials of the long name (upper case), as the old {@code zoneAbbr} did.
     */
    public static String zoneAbbreviation(ZoneId zone, Instant instant) {
        String longName = zoneLongName(zone, instant);
        String englishAbbreviation = HONOLULU_ZONE_IDS.contains(zone.getId()) && HONOLULU_ABBREVIATIONS.containsKey(longName)
                ? HONOLULU_ABBREVIATIONS.get(longName) : ENGLISH_ABBREVIATIONS.get(longName);
        return englishAbbreviation != null ? englishAbbreviation : initialsOf(longName);
    }

    /** "India Standard Time (IST)". */
    public static String zoneLabel(ZoneId zone, Instant instant) {
        return zoneLongName(zone, instant) + " (" + zoneAbbreviation(zone, instant) + ")";
    }

    /** ICU's name for a bare offset: "GMT+05:30", "Greenwich Mean Time" at zero. */
    private static String offsetName(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? GREENWICH_MEAN_TIME : GREENWICH + offset.getId();
    }

    /** The first character of every word, upper case ("Nepal Time" gives "NT"), like the old {@code initialsOf}. */
    private static String initialsOf(String name) {
        StringBuilder initials = new StringBuilder();
        for (String word : name.split("\\s+")) {
            if (!word.isEmpty()) initials.append(word.charAt(0));
        }
        return initials.toString().toUpperCase(Locale.ROOT);
    }
}
