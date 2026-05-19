/*
 * Copyright 2018 CapTech Ventures, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.captech.alfred.authentication;

import com.captech.alfred.dataConnections.DataUserStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.switchuser.SwitchUserFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

@Configuration
class SecurityConfiguration {

    @Autowired
    DataUserStoreService userStore;

    @Bean
    public PasswordEncoder encoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService() {
        return new UserDetailsService() {
            @Override
            public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
                User user = userStore.findByUsername(username);
                if (user == null || user.getUsername() == null) {
                    throw new UsernameNotFoundException("could not find the user '" + username + "'");
                }
                Set<String> rolesAndPermissions = new HashSet<>();
                for (Role role : user.getRoles()) {
                    rolesAndPermissions.add("ROLE_" + role.getName());
                    for (String perm : role.getPermissions()) {
                        rolesAndPermissions.add("PERM_" + perm);
                    }
                }
                for (String perm : user.getPermissions()) {
                    rolesAndPermissions.add("PERM_" + perm);
                }
                boolean expired = false;
                if (user.getPasswordExpiry() != null && (new Date()).compareTo(user.getPasswordExpiryAsDate()) >= 0) {
                    expired = true;
                }
                /**
                 * public User(String username, String password, boolean
                 * enabled, boolean accountNonExpired, boolean
                 * credentialsNonExpired, boolean accountNonLocked, Collection<?
                 * extends GrantedAuthority> authorities)
                 */

                return new ExpiringUser(user.getUsername(), user.getPassword(), AuthorityUtils.createAuthorityList(
                        rolesAndPermissions.toArray(new String[rolesAndPermissions.size()])), expired);
            }
        };
    }
}

@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Configuration
class WebSecurityConfig {

    @Bean
    @Order(3)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        http.addFilterAfter(new ChangePasswordFilter(), SwitchUserFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/js/**", "/css/**", "/webjars/**").permitAll()
                        .anyRequest().fullyAuthenticated())
                .httpBasic(httpBasic -> httpBasic.realmName("Alfred"))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}

@Configuration
class PasswordSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain passwordSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/authentication/user/password")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/authentication/user/password").fullyAuthenticated())
                .httpBasic(httpBasic -> httpBasic.realmName("Alfred"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}

@Configuration
class CurrentUserSecurityConfig {
    @Bean
    @Order(2)
    SecurityFilterChain currentUserSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.GET, "/authentication/user"))
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().fullyAuthenticated())
                .httpBasic(httpBasic -> httpBasic.realmName("Alfred"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
