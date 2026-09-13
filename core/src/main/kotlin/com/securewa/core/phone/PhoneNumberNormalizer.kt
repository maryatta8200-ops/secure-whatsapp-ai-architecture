package com.securewa.core.phone

/**
 * E.164 normalisation and validation.
 *
 * E.164 (ITU-T Recommendation E.164) defines an international public
 * telecommunication number as:
 *  - a leading `+`,
 *  - a country calling code (1 to 3 digits),
 *  - a national significant number,
 *  - at most 15 digits in total.
 *
 * This implementation performs real format validation against a country
 * calling code table. It intentionally has no third-party dependency so the
 * domain module stays dependency-free and testable offline. It is not a
 * replacement for a full numbering-plan metadata library: where a carrier
 * level guarantee is required, validate the number against the Twilio Lookup
 * API from the number configuration screen before marking a channel
 * connected.
 */
object PhoneNumberNormalizer {

    /** Maximum total digits permitted by E.164. */
    const val MAX_DIGITS = 15

    /** Shortest plausible total length (country code + subscriber number). */
    const val MIN_DIGITS = 8

    /** Country calling code -> ISO 3166-1 alpha-2 region (ITU-T E.164 assignments). */
    private val COUNTRY_CALLING_CODES: Map<String, String> = mapOf(
        "1" to "US", "7" to "RU", "20" to "EG", "27" to "ZA", "30" to "GR", "31" to "NL",
        "32" to "BE", "33" to "FR", "34" to "ES", "36" to "HU", "39" to "IT", "40" to "RO",
        "41" to "CH", "43" to "AT", "44" to "GB", "45" to "DK", "46" to "SE", "47" to "NO",
        "48" to "PL", "49" to "DE", "51" to "PE", "52" to "MX", "53" to "CU", "54" to "AR",
        "55" to "BR", "56" to "CL", "57" to "CO", "58" to "VE", "60" to "MY", "61" to "AU",
        "62" to "ID", "63" to "PH", "64" to "NZ", "65" to "SG", "66" to "TH", "81" to "JP",
        "82" to "KR", "84" to "VN", "86" to "CN", "90" to "TR", "91" to "IN", "92" to "PK",
        "93" to "AF", "94" to "LK", "95" to "MM", "98" to "IR", "211" to "SS", "212" to "MA",
        "213" to "DZ", "216" to "TN", "218" to "LY", "220" to "GM", "221" to "SN", "222" to "MR",
        "223" to "ML", "224" to "GN", "225" to "CI", "226" to "BF", "227" to "NE", "228" to "TG",
        "229" to "BJ", "230" to "MU", "231" to "LR", "232" to "SL", "233" to "GH", "234" to "NG",
        "235" to "TD", "236" to "CF", "237" to "CM", "238" to "CV", "239" to "ST", "240" to "GQ",
        "241" to "GA", "242" to "CG", "243" to "CD", "244" to "AO", "245" to "GW", "246" to "IO",
        "248" to "SC", "249" to "SD", "250" to "RW", "251" to "ET", "252" to "SO", "253" to "DJ",
        "254" to "KE", "255" to "TZ", "256" to "UG", "257" to "BI", "258" to "MZ", "260" to "ZM",
        "261" to "MG", "262" to "RE", "263" to "ZW", "264" to "NA", "265" to "MW", "266" to "LS",
        "267" to "BW", "268" to "SZ", "269" to "KM", "290" to "SH", "291" to "ER", "297" to "AW",
        "298" to "FO", "299" to "GL", "350" to "GI", "351" to "PT", "352" to "LU", "353" to "IE",
        "354" to "IS", "355" to "AL", "356" to "MT", "357" to "CY", "358" to "FI", "359" to "BG",
        "370" to "LT", "371" to "LV", "372" to "EE", "373" to "MD", "374" to "AM", "375" to "BY",
        "376" to "AD", "377" to "MC", "378" to "SM", "380" to "UA", "381" to "RS", "382" to "ME",
        "385" to "HR", "386" to "SI", "387" to "BA", "389" to "MK", "420" to "CZ", "421" to "SK",
        "423" to "LI", "500" to "FK", "501" to "BZ", "502" to "GT", "503" to "SV", "504" to "HN",
        "505" to "NI", "506" to "CR", "507" to "PA", "508" to "PM", "509" to "HT", "590" to "GP",
        "591" to "BO", "592" to "GY", "593" to "EC", "595" to "PY", "597" to "SR", "598" to "UY",
        "599" to "CW", "670" to "TL", "672" to "NF", "673" to "BN", "674" to "NR", "675" to "PG",
        "676" to "TO", "677" to "SB", "678" to "VU", "679" to "FJ", "680" to "PW", "681" to "WF",
        "682" to "CK", "683" to "NU", "685" to "WS", "686" to "KI", "687" to "NC", "688" to "TV",
        "689" to "PF", "691" to "FM", "692" to "MH", "850" to "KP", "852" to "HK", "853" to "MO",
        "855" to "KH", "856" to "LA", "880" to "BD", "886" to "TW", "960" to "MV", "961" to "LB",
        "962" to "JO", "963" to "SY", "964" to "IQ", "965" to "KW", "966" to "SA", "967" to "YE",
        "968" to "OM", "970" to "PS", "971" to "AE", "972" to "IL", "973" to "BH", "974" to "QA",
        "975" to "BT", "976" to "MN", "977" to "NP", "992" to "TJ", "993" to "TM", "994" to "AZ",
        "995" to "GE", "996" to "KG", "998" to "UZ"
    )

    /** Reverse index used when interpreting a national-format input. */
    private val REGION_TO_CALLING_CODE: Map<String, String> =
        COUNTRY_CALLING_CODES.entries.associate { (code, region) -> region to code }

    private val SEPARATORS = Regex("[\\s\\-(). ]")
    private val NANP = "1"

    /**
     * Normalises [rawInput] to E.164.
     *
     * @param defaultRegion ISO 3166-1 alpha-2 region used to interpret an input
     *                      written in national format (for example `0300 1234567`).
     *                      Required for national-format input, ignored for
     *                      `+`-prefixed and `00`-prefixed input.
     */
    fun normalize(rawInput: String, defaultRegion: String? = null): NormalizationResult {
        if (rawInput.isBlank()) return NormalizationResult.Failure(NormalizationFailure.EMPTY_INPUT)

        val compact = rawInput.trim().replace(SEPARATORS, "")
        if (compact.isEmpty()) return NormalizationResult.Failure(NormalizationFailure.EMPTY_INPUT)
        if (compact.any { !it.isDigit() && it != '+' }) {
            return NormalizationResult.Failure(NormalizationFailure.INVALID_CHARACTERS)
        }
        if (compact.count { it == '+' } > 1 || (compact.contains('+') && !compact.startsWith("+"))) {
            return NormalizationResult.Failure(NormalizationFailure.INVALID_CHARACTERS)
        }

        val digits = when {
            compact.startsWith("+") -> compact.substring(1)
            compact.startsWith("00") && compact.length > 2 -> compact.substring(2)
            !defaultRegion.isNullOrBlank() -> {
                val callingCode = REGION_TO_CALLING_CODE[defaultRegion.trim().uppercase()]
                    ?: return NormalizationResult.Failure(NormalizationFailure.UNKNOWN_DEFAULT_REGION)
                val national = if (compact.startsWith("0")) compact.substring(1) else compact
                callingCode + national
            }
            else -> return NormalizationResult.Failure(NormalizationFailure.MISSING_COUNTRY_CODE)
        }

        return validateDigits(digits)
    }

    /** True when [value] is already a syntactically valid E.164 number. */
    fun isE164(value: String): Boolean = normalize(value).isSuccess

    /** Digits of an E.164 number without the leading `+`, or `null` when invalid. */
    fun digitsOf(value: String): String? {
        val result = normalize(value)
        return if (result is NormalizationResult.Success) result.number.e164.removePrefix("+") else null
    }

    private fun validateDigits(digits: String): NormalizationResult {
        if (digits.any { !it.isDigit() }) {
            return NormalizationResult.Failure(NormalizationFailure.INVALID_CHARACTERS)
        }
        if (digits.length < MIN_DIGITS) return NormalizationResult.Failure(NormalizationFailure.TOO_SHORT)
        if (digits.length > MAX_DIGITS) return NormalizationResult.Failure(NormalizationFailure.TOO_LONG)

        val callingCode = matchCallingCode(digits)
            ?: return NormalizationResult.Failure(NormalizationFailure.UNKNOWN_COUNTRY_CALLING_CODE)
        val national = digits.substring(callingCode.length)

        // North American Numbering Plan: 1 + a 10 digit national number whose
        // area code and central office code may not start with 0 or 1.
        if (callingCode == NANP) {
            if (digits.length != 11) {
                return NormalizationResult.Failure(NormalizationFailure.INVALID_LENGTH_FOR_REGION)
            }
            if (national[0] == '0' || national[0] == '1' || national[3] == '0' || national[3] == '1') {
                return NormalizationResult.Failure(NormalizationFailure.INVALID_LENGTH_FOR_REGION)
            }
        }
        if (national.length < 4) {
            return NormalizationResult.Failure(NormalizationFailure.TOO_SHORT)
        }

        return NormalizationResult.Success(
            NormalizedNumber(
                e164 = "+$digits",
                countryCallingCode = callingCode,
                nationalSignificantNumber = national,
                regionCode = COUNTRY_CALLING_CODES[callingCode]
            )
        )
    }

    /** Longest-prefix match over the country calling code table (3, then 2, then 1 digits). */
    private fun matchCallingCode(digits: String): String? {
        for (length in 3 downTo 1) {
            if (digits.length > length) {
                val candidate = digits.substring(0, length)
                if (COUNTRY_CALLING_CODES.containsKey(candidate)) return candidate
            }
        }
        return null
    }
}

/** An E.164 number that passed validation. */
data class NormalizedNumber(
    val e164: String,
    val countryCallingCode: String,
    val nationalSignificantNumber: String,
    val regionCode: String?
) {
    /** Digits without the leading `+`, the form stored for indexing. */
    val digits: String get() = e164.removePrefix("+")
}

/** Outcome of [PhoneNumberNormalizer.normalize]. */
sealed interface NormalizationResult {
    val isSuccess: Boolean
        get() = this is Success

    data class Success(val number: NormalizedNumber) : NormalizationResult
    data class Failure(val reason: NormalizationFailure) : NormalizationResult
}

/** Why a number was rejected. Values are user-facing and safe to display. */
enum class NormalizationFailure(val message: String) {
    EMPTY_INPUT("Enter a phone number."),
    INVALID_CHARACTERS("A phone number may only contain digits, spaces and the characters + - ( ) ."),
    MISSING_COUNTRY_CODE("Enter the number in international format (for example +923001234567) or set a default country."),
    UNKNOWN_DEFAULT_REGION("The selected country is not a recognised calling-code region."),
    UNKNOWN_COUNTRY_CALLING_CODE("The country calling code is not a recognised E.164 code."),
    TOO_SHORT("The number is too short to be a valid E.164 number."),
    TOO_LONG("The number is too long: E.164 allows at most 15 digits."),
    INVALID_LENGTH_FOR_REGION("The national number length is not valid for that country calling code.")
}
