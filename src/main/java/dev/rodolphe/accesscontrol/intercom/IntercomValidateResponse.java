package dev.rodolphe.accesscontrol.intercom;

/**
 * Always returned with HTTP 200, refusal included: "this code is not valid" is a normal answer to a
 * well-formed question, not a transport error. Only a bad intercom key produces a 401.
 */
record IntercomValidateResponse(
        boolean allowed,
        String doorName,
        String doorBleLocalName,
        String reason
) {
    public static IntercomValidateResponse refused(String reason) {
        return new IntercomValidateResponse(false, null, null, reason);
    }

    public static IntercomValidateResponse granted(String doorName, String doorBleLocalName) {
        return new IntercomValidateResponse(true, doorName, doorBleLocalName, null);
    }
}
