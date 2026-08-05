package com.example.springbootrag.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP Basic over an in-memory user list (see {@link SecurityProperties}).
 *
 * <p>Deliberately minimal: the lesson being learned here is retrieval-time authorisation, not
 * login flows. What matters is that the identity used for filtering arrives through this filter
 * chain and cannot be supplied by the caller.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Stateless Basic auth: there is no session and no browser form to forge against,
            // so the CSRF token would protect nothing. Re-enable it the moment a cookie session
            // or a form login appears.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Groups become authorities named {@code GROUP_<name>}, which {@link CurrentUser} reads back
     * into a {@link SearchContext}. Passwords use the {noop} encoder - sandbox only, see
     * {@link SecurityProperties}.
     */
    @Bean
    UserDetailsService userDetailsService(SecurityProperties props) {
        List<UserDetails> users = new ArrayList<>();
        for (SecurityProperties.User u : props.getUsers()) {
            users.add(User.withUsername(u.getUsername())
                    .password("{noop}" + u.getPassword())
                    .authorities(u.getGroups().stream()
                            .map(g -> new SimpleGrantedAuthority(CurrentUser.GROUP_PREFIX + g))
                            .toList())
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
