package com.vio.vioaiagent.pokemon.gym;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@RestController @RequestMapping("/pokemon/gym-challenges")
@Profile("local & !prod & !demo") @ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class GymChallengeController {
 private final GymChallengeService service;
 private final GymBattleCalculator damage;
 public GymChallengeController(GymChallengeService service,GymBattleCalculator damage){this.service=service;this.damage=damage;}
 @GetMapping public com.fasterxml.jackson.databind.JsonNode catalog(@RequestParam(defaultValue="initial") String phase){return service.catalog(phase);}
 @GetMapping("/resources") public java.util.List<GymResourcePolicy.Rule> resources(){return GymResourcePolicy.catalog();}
 @PostMapping("/analyze") public GymChallengeService.Report analyze(@RequestBody GymChallengeService.Request request){return service.analyze(request);}
 @PostMapping("/damage") public com.fasterxml.jackson.databind.JsonNode damage(@RequestBody GymBattleCalculator.Request request){return damage.calculate(request);}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
 public java.util.Map<String,String> invalid(IllegalArgumentException error){return java.util.Map.of("message",error.getMessage());}
}
