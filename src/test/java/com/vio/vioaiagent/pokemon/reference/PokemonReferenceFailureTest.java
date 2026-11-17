package com.vio.vioaiagent.pokemon.reference;
import org.junit.jupiter.api.Test;
import com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PokemonReferenceFailureTest {
 @Test void unknownChineseNameIsAbsenceWhileNameTableFailureRemainsUnavailable() {
  var client=mock(PokeApiClient.class);
  when(client.names()).thenReturn(new PokeApiClient.Snapshot("pokemon_species_id,local_language_id,name,genus\n7,12,杰尼龟,小龟", "", java.time.Instant.now(), ""));
  var repository=new LocalPokeApiRepository(new com.fasterxml.jackson.databind.ObjectMapper(),client);
  var tool=new PokemonReferenceTools(repository);
  assertEquals("POKEMON_REFERENCE_NOT_FOUND",assertThrows(PokemonResourceNotFoundException.class,()->tool.lookupPokemonReference("验收专用不存在物种qafinal17","pokeapi-reference-v1")).getMessage());
  verify(client,never()).read(anyString(),anyString());
  when(client.names()).thenThrow(new PokeApiUnavailableException("name service unavailable",null));
  var unavailable=new PokemonReferenceTools(new LocalPokeApiRepository(new com.fasterxml.jackson.databind.ObjectMapper(),client));
  assertThrows(PokeApiUnavailableException.class,()->unavailable.lookupPokemonReference("另一个未知名称","pokeapi-reference-v1"));
 }
 @Test void onlyTypedSpeciesAbsenceGetsStableMarker(){
  var repo=mock(LocalPokeApiRepository.class);var tool=new PokemonReferenceTools(repo);
  when(repo.detail("unknown","pokeapi-reference-v1")).thenThrow(new PokemonResourceNotFoundException("404"));
  assertEquals("POKEMON_REFERENCE_NOT_FOUND",assertThrows(PokemonResourceNotFoundException.class,()->tool.lookupPokemonReference("unknown","pokeapi-reference-v1")).getMessage());
  when(repo.detail("known","pokeapi-reference-v1")).thenThrow(new IllegalStateException("timeout"));
  assertEquals("timeout",assertThrows(IllegalStateException.class,()->tool.lookupPokemonReference("known","pokeapi-reference-v1")).getMessage());
 }
}
