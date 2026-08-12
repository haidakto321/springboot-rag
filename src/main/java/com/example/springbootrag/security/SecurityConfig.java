package com.example.springbootrag.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
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
 *
 * <p>{@code @EnableMethodSecurity} is on because quarantine release is an ACTION permission, and a
 * path matcher in the filter chain would attach that rule to a URL shape rather than to the method
 * it protects - a later rename would disarm it with no compile error and no failing test.
 */
@Configuration
@EnableMethodSecurity
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
     * into a {@link SearchContext}; roles become {@code ROLE_<name>}, which {@code @PreAuthorize}
     * checks. Both travel as authorities so Spring Security owns them end to end, and neither can
     * be supplied by the caller. Passwords use the {noop} encoder - sandbox only, see
     * {@link SecurityProperties}.
     */
    @Bean
    UserDetailsService userDetailsService(SecurityProperties props) {
        List<UserDetails> users = new ArrayList<>();
        for (SecurityProperties.User u : props.getUsers()) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (String group : u.getGroups()) {
                authorities.add(new SimpleGrantedAuthority(CurrentUser.GROUP_PREFIX + group));
            }
            for (String role : u.getRoles()) {
                authorities.add(new SimpleGrantedAuthority(Roles.PREFIX + role));
            }
            users.add(User.withUsername(u.getUsername())
                    .password("{noop}" + u.getPassword())
                    .authorities(authorities)
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
