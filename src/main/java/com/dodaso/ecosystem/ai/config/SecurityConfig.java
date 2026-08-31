package com.dodaso.ecosystem.ai.config;


import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  // @Autowired
  // private JwtAuthEntryPoint unauthorizedHandler;

  // @Autowired
  // JwtAuthTokenFilter jwtAuthTokenFilter;

  @Setter(onMethod = @__({@Autowired}))
  private SimpleCORSFilter simpleCORSFileter;

  @Value("${spring.profiles.active}")
  private String activeProfile;

  private String[] urls2NotRequireAuthorization = new String[]{"/ping", "/authenticateToken",
      "/unauthorized.xhtml",
      "/**/jakarta.faces.resource/*.css.xhtml*", "/**/jakarta.faces.resource/*.js.xhtml*",
      "/**/jakarta.faces.resource/*.gif.xhtml*", "/**/jakarta.faces.resource/*.woff.xhtml*",
      "/**/jakarta.faces.resource/*.woff2.xhtml*", "/**/jakarta.faces.resource/*.ttf.xhtml*",
      "/**/jakarta.faces.resource/*.svg.xhtml*", "/**/jakarta.faces.resource/*.png.xhtml*",
      "/**/jakarta.faces.resource/*.eot.xhtml*", "/**/jakarta.faces.resource/**/*.css.xhtml*",
      "/**/jakarta.faces.resource/**/*.js.xhtml*", "/**/jakarta.faces.resource/**/*.gif.xhtml*",
      "/**/jakarta.faces.resource/**/*.ttf.xhtml*", "/**/jakarta.faces.resource/**/*.woff.xhtml*",
      "/**/jakarta.faces.resource/**/*.woff2.xhtml*", "/**/jakarta.faces.resource/**/*.svg.xhtml*",
      "/**/jakarta.faces.resource/**/*.png.xhtml*", "/**/jakarta.faces.resource/**/*.eot.xhtml*"};

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // In case we want security to be disable for restful ws layer

    if (activeProfile.equals("local") || activeProfile.equals("test")) {
      http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(authorize -> authorize.requestMatchers("/**").permitAll());
      return http.build();
    }

//        if(activeProfile.equals("local")) {
//            http.csrf().disable().authorizeRequests().anyRequest().permitAll();
//        } else {
//            http.addFilterBefore(simpleCORSFileter, SessionManagementFilter.class)
//                    //.csrf().disable().exceptionHandling().authenticationEntryPoint(unauthorizedHandler)
//                    .csrf().disable().exceptionHandling().authenticationEntryPoint(getUnauthorizedHandler())
//                    //.and().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//                    .and().authorizeRequests()
//                    .antMatchers(urls2NotRequireAuthorization) //"/", "/authenticateToken", "/unauthorized.xhtml")
//                    .permitAll().anyRequest().authenticated()
//                    .and()
//                    //.addFilterBefore(jwtAuthTokenFilter, UsernamePasswordAuthenticationFilter.class)
//                    .addFilterBefore(getJwtAuthTokenFilter(), UsernamePasswordAuthenticationFilter.class)
//                    .headers().cacheControl();
//        }
    return http.build();
  }

//    private JwtAuthTokenFilter getJwtAuthTokenFilter() {
//        return new JwtAuthTokenFilter();
//    }
//
//    private JwtAuthEntryPoint getUnauthorizedHandler() {
//        return new JwtAuthEntryPoint();
//    }

}
