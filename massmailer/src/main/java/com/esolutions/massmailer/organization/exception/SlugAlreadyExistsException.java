package com.esolutions.massmailer.organization.exception;

/**
 * Thrown when a registration request uses a slug that is already taken.
 * Maps to HTTP 409 Conflict.
 */
public class SlugAlreadyExistsException extends RuntimeException {

    private final String slug;

    public SlugAlreadyExistsException(String slug) {
        super("Slug already registered: " + slug);
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
