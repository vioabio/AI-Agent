package com.vio.vioaiagent.pokemon.reference;

/** A bounded upstream failure, distinct from missing facts and application defects. */
public class PokeApiUnavailableException extends IllegalStateException {
    public PokeApiUnavailableException(String message, Throwable cause) { super(message, cause); }
}
