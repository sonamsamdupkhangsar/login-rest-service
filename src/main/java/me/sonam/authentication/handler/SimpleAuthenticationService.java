package me.sonam.authentication.handler;

import jakarta.annotation.PostConstruct;
import me.sonam.authentication.carrier.ClientOrganizationUserWithRole;
import me.sonam.authentication.repo.AuthenticationRepository;
import me.sonam.authentication.repo.entity.Authentication;
import me.sonam.security.headerfilter.ReactiveRequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;


public class SimpleAuthenticationService implements AuthenticationService {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleAuthenticationService.class);

    /**
     * This will validate username and password matches against a stored table
     * and call jwt-rest-service to generate JWT token to issue for the caller.
     * This will also validate the API key matches the stored entry.
     * @param userMono contains the User object with username/password
     * @return
     */

    @Value("${role-rest-service.root}${role-rest-service.user-role}")
    private String roleEp;

    @Value("${role-rest-service.client-organization-user-role}")
    private String clientOrganizationUserRoleEp;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthenticationRepository authenticationRepository;

    @Autowired
    private ReactiveRequestContextHolder reactiveRequestContextHolder;

    private WebClient.Builder webClientBuilder;

    public SimpleAuthenticationService(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void setWebClient() {
        webClientBuilder.filter(reactiveRequestContextHolder.headerFilter());
    }

    @Override
    public Mono<Map<String, String>> authenticate(Mono<AuthenticationPassword> authenticationPasswordMono) {
        /**
         *  .map(authentication -> !authentication.getActive())
         *                 .switchIfEmpty(Mono.error(new AuthenticationException("Authentication not active, activate your acccount first")))
         */
        return authenticationPasswordMono.flatMap(authenticationPassword ->
                authenticationRepository.existsById(authenticationPassword.getAuthenticationId())
                        .filter(aBoolean -> aBoolean)
                        .switchIfEmpty(Mono.error(new AuthenticationException("authentication does not exist with authId")))
                        .flatMap(aBoolean -> authenticationRepository.existsByAuthenticationIdAndActiveTrue(authenticationPassword.getAuthenticationId()))
                        .doOnNext(aBoolean -> LOG.info("aboolean is {}", aBoolean))
                        .filter(aBoolean -> aBoolean)
                        .switchIfEmpty(Mono.error(new AuthenticationException("Authentication not active, activate your acccount first")))
                        .flatMap(aBoolean -> authenticationRepository.findById(authenticationPassword.getAuthenticationId()))
                        .flatMap(authentication -> {
                            if (passwordEncoder.matches(authenticationPassword.getPassword(), authentication.getPassword())) {
                                return Mono.just(authentication);
                            }
                            return Mono.error(new AuthenticationException("no authentication found with username and password"));
                        })
                        //.switchIfEmpty(Mono.error(new AuthenticationException("no authentication found with username and password")))
                        // check if user is in organiation
                        // step: check if there is a record with user with clientId and check if that organizatino has this user in it
                        .flatMap(authentication ->
                                {
                                    if (authenticationPassword.getOrganizationId() != null) {
                                        LOG.info("get organization roles when organization-id is set");
                                        return getClientOrganizationUserRoles(authentication.getUserId(),
                                                    authenticationPassword.getOrganizationId(),
                                                    authenticationPassword.getClientId())
                                                .zipWith(Mono.just(authentication));
                                    }
                                    else {
                                        return getUserRolesForClientId(authentication.getUserId().toString(),
                                                    authenticationPassword.getClientId())
                                                .zipWith(Mono.just(authentication));
                                    }
                                }
                        ).flatMap(objects -> {
                            return Mono.just(Map.of("roles", objects.getT1().toString()
                            , "userId", objects.getT2().getUserId().toString()
                            , "message", "Authentication successful"));
                        }));
                        /*.flatMap(stringMap -> {
                            LOG.info("map contains {}", stringMap);

                            return Mono.just(stringMap);
                        }));*/
    }

                /*
                .flatMap(clientUserRole -> {
                    // this step sends in a Hmac that contains this application's algorithm, secretKey, and a json
                    // to jwt-rest-service to validate the request is coming from a verified cliend for getting a
                    // jwt token

                    // since we don't have the jwt-rest-service anymore, make a request to the authorization server

                    LOG.info("clientUserRole: {}", clientUserRole);
                    LOG.info("clientUserRole.userRole {}", clientUserRole.get("userRole"));
                    LOG.info("clientUserRole.groupNames {}", clientUserRole.get("groupNames"));

                    final StringBuilder userJwtJson = new StringBuilder("{\n");
                    userJwtJson.append("  \"sub\": \"").append(authenticationPassword.getAuthenticationId()).append("\",\n")
                            .append("  \"scope\": \""+scope+"\",\n")
                            .append("  \"clientId\": \"").append(authenticationPassword.getClientId()).append("\",\n")
                            .append("  \"aud\": \""+audience+"\",\n")
                            .append("  \"role\": \"").append(clientUserRole.get("userRole")).append("\",\n")
                            .append("  \"groups\": \"");
                            final String groupNames = clientUserRole.get("groupNames").toString();
                            userJwtJson.append(groupNames);
                            userJwtJson.append("\",\n")
                            .append("  \"expiresInSeconds\": "+expiresInSeconds+"\n")
                            .append("}\n");

                    final StringBuilder jsonString = new StringBuilder("{\n");
                    jsonString.append("  \"sub\": \"").append(hmacClient.getClientId()).append("\",\n")
                            .append("  \"scope\": \"").append(hmacClient.getClientId()).append("\",\n")
                            .append("  \"clientId\": \"").append(hmacClient.getClientId()).append("\",\n")
                            .append("  \"aud\": \"service\",\n")
                            .append("  \"role\": \"service\",\n")
                            .append("  \"groups\": \"service\",\n")
                            .append("  \"expiresInSeconds\": 300,\n")
                            .append(" \"userJwt\": ").append(userJwtJson.toString())
                            .append("}\n");


                    LOG.info("jsonString: {}", jsonString);

                    final String hmac = Util.getHmac(hmacClient.getAlgorithm(), jsonString.toString(), hmacClient.getSecretKey());
                    LOG.info("creating hmac for jwt-rest-service: {}", jwtServiceEndpoint);

                    WebClient.ResponseSpec responseSpec = webClientBuilder.build().post().uri(jwtServiceEndpoint)
                            .headers(httpHeaders -> httpHeaders.add(HttpHeaders.AUTHORIZATION, hmac))
                            .bodyValue(jsonString)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve();
                    return responseSpec.bodyToMono(Map.class).map(map -> {
                        LOG.info("got jwt token: {}", map.get("token"));
                        return map.get("token").toString();
                    }).onErrorResume(throwable -> {
                                LOG.error("account rest call failed: {}", throwable.getMessage());
                                if (throwable instanceof WebClientResponseException) {
                                    WebClientResponseException webClientResponseException = (WebClientResponseException) throwable;
                                    LOG.error("error body contains: {}", webClientResponseException.getResponseBodyAsString());
                                    return Mono.error(new AuthenticationException("jwt rest  api call failed with error: " +
                                            webClientResponseException.getResponseBodyAsString()));
                                }
                                else {
                                    return Mono.error(new AuthenticationException("Application api call failed with error: " +throwable.getMessage()));
                                }
                            });
                }));
    }*/

    @Override
    public Mono<String> createAuthentication(Mono<AuthTransfer> authTransferMono) {
        LOG.info("Create authentication");
        return authTransferMono
                .flatMap(authTransfer -> authenticationRepository.existsByAuthenticationIdAndActiveTrue(authTransfer.getAuthenticationId())
                         .filter(aBoolean -> !aBoolean)
                         .switchIfEmpty(Mono.error(new AuthenticationException("Authentication is already active with authenticationId")))
                         .flatMap(aBoolean -> {
                             LOG.info("delete by id where active is false");
                             return authenticationRepository.deleteByAuthenticationIdAndActiveFalse(authTransfer.getAuthenticationId());
                         })
                         .flatMap(integer -> {
                             LOG.info("create authentication: {}, password: {}", authTransfer.getAuthenticationId(), authTransfer.getPassword());
                             return Mono.just(new Authentication(
                                     authTransfer.getAuthenticationId(), passwordEncoder.encode(authTransfer.getPassword()), authTransfer.getUserId(),
                                     null, false, LocalDateTime.now(), true));
                         })
                         .flatMap(authentication -> authenticationRepository.save(authentication))
                         .flatMap(authentication1 -> {
                             LOG.info("authentication created successfully for authId: {}", authentication1);
                            return Mono.just(authentication1.getAuthenticationId());
                         }));
    }

    @Override
    public Mono<String> activateAuthentication(String authenticationId) {
        LOG.info("activate authentication");

        return authenticationRepository.updateAuthenticationActiveTrue(authenticationId)
                .thenReturn("activated: "+authenticationId);
    }

    /**
     * this will be called by a non-logged in user, whcih will have a secret
     * @param authenticationId
     * @return
     */
    @Override
    public Mono<String> updatePassword(String authenticationId, String password) {
        LOG.info("update password for auth: '{}', password: '{}'", authenticationId, password);
        final String encodedPassword = passwordEncoder.encode(password);

        authenticationRepository.updatePassword(authenticationId, encodedPassword)
                .subscribe(integer -> LOG.info("row updated: {}", integer));
        return Mono.just("password updated");
    }

    @Override
    public Mono<String> delete() {
        LOG.info("delete authentication");
        return
                ReactiveSecurityContextHolder.getContext().flatMap(securityContext -> {
                    org.springframework.security.core.Authentication authentication = securityContext.getAuthentication();

                    Jwt jwt = (Jwt) authentication.getPrincipal();
                    String userIdString = jwt.getClaim("userId");
                    LOG.info("delete authentication data for userId: {}", userIdString);

                    UUID userId = UUID.fromString(userIdString);

                    return authenticationRepository.deleteByUserId(userId)
                            .doOnNext(integer -> LOG.info("deleted with rows change: {}", integer))
                            .thenReturn("deleted Authentication with userId: " + userId);
                });
    }

    @Override
    public Mono<String> deleteByAuthenticationId(String authenticationId) {
        LOG.info("delete authentication by authenticationId: '{}'", authenticationId);

        return authenticationRepository.deleteById(authenticationId)
                .doOnNext(integer -> LOG.info("deleted with rows change: {}", integer))
                .thenReturn("deleted Authentication with authenticationId: " + authenticationId+" completed");

    }

    private Mono<Map<String, ?>> getUserRoleForClientId(String userId, String clientId) {
            LOG.info("role endpoint: {}", roleEp);
            WebClient.ResponseSpec responseSpec = webClientBuilder.build().get().uri(
                            roleEp.replace("{clientId}", clientId)
                                    .replace("{userId}", userId))
                    .retrieve();
            return responseSpec.bodyToMono(Map.class).map(map -> {
                LOG.info("got role: {}", map);

                if (map.get("roleName") != null) {
                    LOG.info("got role: {}", map.get("roleName"));
                    return Map.of("roleName", map.get("roleName"));
                }
                else {
                    return Map.of("roleName", "");
                }
            }).onErrorResume(throwable -> {
                LOG.error("role  rest call failed: {}", throwable.getMessage());
                if (throwable instanceof WebClientResponseException) {
                    WebClientResponseException webClientResponseException = (WebClientResponseException) throwable;
                    LOG.error("error body contains: {}", webClientResponseException.getResponseBodyAsString());
                }

                return Mono.just(Map.of("roleName", ""));
            });
        }

    private Mono<List<String>> getClientOrganizationUserRoles(UUID userId, UUID organizationId, String clientId) {
        final String endpoint = clientOrganizationUserRoleEp.replace("{clientId}", clientId)
                .replace("{organizationId}", organizationId.toString())
                .replace("{userId}", userId.toString());

        LOG.info("get client organization user role endpoint: {}", endpoint);

        WebClient.ResponseSpec responseSpec = webClientBuilder.build().get().uri(endpoint).retrieve();

        return responseSpec.bodyToMono(new ParameterizedTypeReference<List<ClientOrganizationUserWithRole>>() {}).flatMap(list -> {
                   List<String> roles = list.stream().map(clientOrganizationUserWithRole ->  clientOrganizationUserWithRole.getUser().getRole().getName())
                           .toList();
                   return Mono.just(roles);
                })
                .onErrorResume(throwable -> {
                    LOG.error("client-organization-user-roles  rest call failed: {}", throwable.getMessage());
                    if (throwable instanceof WebClientResponseException webClientResponseException) {
                        LOG.error("error body contains: {}", webClientResponseException.getResponseBodyAsString());
                    }

                    return Mono.just(List.of(""));
                });
    }

    private Mono<List<String>> getUserRolesForClientId(String userId, String clientId) {
        LOG.info("role endpoint: {}", roleEp);
        WebClient.ResponseSpec responseSpec = webClientBuilder.build().get().uri(
                        roleEp.replace("{clientId}", clientId)
                                .replace("{userId}", userId))
                .retrieve();

        return responseSpec.bodyToFlux(Map.class).flatMap(map -> {
            if (map.get("roleName") != null) {
                LOG.info("got role: {}", map.get("roleName"));
                //return Mono.just(Map.of("roleName", map.get("roleName")));
                return Mono.just(map.get("roleName").toString());
            }
            else {
                //return Mono.just(Map.of("roleName", ""));
                return Mono.just("");
            }
        }).collectList()
                .onErrorResume(throwable -> {
                    LOG.error("role  rest call failed: {}", throwable.getMessage());
                    if (throwable instanceof WebClientResponseException) {
                        WebClientResponseException webClientResponseException = (WebClientResponseException) throwable;
                        LOG.error("error body contains: {}", webClientResponseException.getResponseBodyAsString());
                    }

                    return Mono.just(List.of(""));
                });
      /*
        return responseSpec.bodyToFlux(Map.class).map(map -> {
            LOG.info("got role: {}", map);

            if (map.get("roleName") != null) {
                LOG.info("got role: {}", map.get("roleName"));
                return Map.of("roleName", map.get("roleName"));
            }
            else {
                return Map.of("roleName", "");
            }
        }).onErrorResume(throwable -> {
            LOG.error("role  rest call failed: {}", throwable.getMessage());
            if (throwable instanceof WebClientResponseException) {
                WebClientResponseException webClientResponseException = (WebClientResponseException) throwable;
                LOG.error("error body contains: {}", webClientResponseException.getResponseBodyAsString());
            }

            return Mono.just(Map.of("roleName", ""));
        });*/
    }
}
