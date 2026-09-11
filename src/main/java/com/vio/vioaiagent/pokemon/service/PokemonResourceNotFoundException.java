package com.vio.vioaiagent.pokemon.service;

/** M1 版本化事实/队伍资源不存在。 */
public class PokemonResourceNotFoundException extends RuntimeException {
    public PokemonResourceNotFoundException(String message) {
        super(message);
    }
}
