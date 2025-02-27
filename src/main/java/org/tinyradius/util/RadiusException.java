package org.tinyradius.util;

/**
 * An exception which occurs on Radius protocol errors like
 * invalid packets or malformed attributes.
 */
public class RadiusException extends Exception {

    public RadiusException(String message) {
        super(message);
    }

    public RadiusException(String message, Throwable cause) {
        super(message, cause);
    }

    public RadiusException(Throwable cause) {
        super(cause);
    }
}
