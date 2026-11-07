package com.vio.vioaiagent.pokemon.service;

/** M1 版本绑定、唯一性或数据库约束冲突。 */
public class PokemonResourceConflictException extends RuntimeException {
    public PokemonResourceConflictException(String message) {
        super(message);
    }

    public PokemonResourceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
