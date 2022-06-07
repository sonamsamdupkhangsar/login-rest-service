package me.sonam.authentication;

import me.sonam.authentication.handler.AuthenticationHandler;
import me.sonam.authentication.handler.AuthenticationService;
import me.sonam.authentication.handler.User;
import me.sonam.authentication.repo.AuthenticationRepository;
import me.sonam.authentication.repo.entity.Authentication;
import org.junit.Before;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;


@EnableAutoConfiguration
@ExtendWith(SpringExtension.class)
@SpringBootTest( webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AuthenticationRestServiceTest {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationRestServiceTest.class);

    @Autowired
    private WebTestClient client;

    @Autowired
    private AuthenticationRepository authenticationRepository;

    @Test
    public void hello() {
        LOG.info("dummy test method for now");
    }

    @Test
    public void createAuthentication() {
        User user = new User("dummy", "pass", "yakApiKey");

        EntityExchangeResult<String> result = client.post().uri("/authenticate")
                .bodyValue(user)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();

        LOG.info("assert result contains authId: {}", result.getResponseBody());
        assertThat(result.getResponseBody()).isEqualTo("dummy");

       /* LOG.info("authenticate with the created authentication");
        client.put().uri("/authenticate")
                .bodyValue(user)
                .exchange().expectStatus().isOk().expectBody(String.class).consumeWith(stringEntityExchangeResult -> LOG.info(
                "response jwt is {}", stringEntityExchangeResult.getResponseBody()));*/

    }

    public void isAccountActive() {
        Authentication authentication = new Authentication("Yakman", "yakpass", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), true, LocalDateTime.now(), true);
        Mono<Authentication> authenticationMono = authenticationRepository.save(authentication);
        Mono<User> userMono = authenticationMono.map(authentication1 -> {
            LOG.info("create user");

            return new User(authentication1.getAuthenticationId(), authentication1.getPassword(), "yakApiKey");
        });

        final String uuid = UUID.randomUUID().toString();
        LOG.info("check for uuid: {}", uuid);

        userMono.as(StepVerifier::create)
                .assertNext(user -> {

                    LOG.info("user: {}", user);
                    client.put().uri("/authenticate")
                            .bodyValue(user)
                            .exchange().expectStatus().isOk().expectBody(String.class).consumeWith(stringEntityExchangeResult -> LOG.info(
                            "response jwt is {}", stringEntityExchangeResult.getResponseBody()));
                }).verifyComplete();

    }
}
