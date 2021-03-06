package com.skillmasters.server;

import java.util.Arrays;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;

import com.google.common.base.Strings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;

import org.springframework.boot.system.ApplicationHome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Configuration;

import com.skillmasters.server.http.middleware.security.FirebaseAuthenticationTokenFilter;
import com.skillmasters.server.http.middleware.security.FirebaseAuthenticationProvider;

@Configuration
@EnableWebSecurity
public class WebSecurityConfiguration extends WebSecurityConfigurerAdapter
{
  private static final String SERVICE_ACCOUNT_PATH = "service_account.json";

  @Autowired
  private FirebaseAuthenticationProvider authenticationProvider;

  @Bean
  @Override
  public AuthenticationManager authenticationManager() throws Exception
  {
    return new ProviderManager(Arrays.asList(authenticationProvider));
  }

  @Bean("authManager")
  @Override
  public AuthenticationManager authenticationManagerBean() throws Exception
  {
    return super.authenticationManagerBean();
  }

  @Bean
  public FirebaseAuth firebaseAuth() throws IOException
  {
    if (FirebaseApp.getApps().size() == 0) {
      GoogleCredentials credentials = null;

      if (System.getenv("GOOGLE_APPLICATION_CREDENTIALS") == null) {
        ClassLoader loader = getClass().getClassLoader();
        InputStream serviceAccount = loader.getResourceAsStream(SERVICE_ACCOUNT_PATH);
        if (serviceAccount == null) {
          String path = new ApplicationHome(ServerApplication.class).getDir() + "/" + SERVICE_ACCOUNT_PATH;
          serviceAccount = new FileInputStream(path);
        }
        credentials = GoogleCredentials.fromStream(serviceAccount);
      } else {
        credentials = GoogleCredentials.getApplicationDefault();
      }

      String projectId = null;
      if (credentials instanceof ServiceAccountCredentials) {
        projectId = ((ServiceAccountCredentials) credentials).getProjectId();
      }

      if (Strings.isNullOrEmpty(projectId)) {
        projectId = System.getenv("GCLOUD_PROJECT");
      }

      FirebaseOptions options = new FirebaseOptions.Builder()
          .setCredentials(credentials)
          .setDatabaseUrl("https://" + projectId + ".firebaseio.com")
          .build();

      FirebaseApp.initializeApp(options);
    }
    return FirebaseAuth.getInstance();
  }

  public FirebaseAuthenticationTokenFilter authenticationTokenFilterBean() throws Exception
  {
    FirebaseAuthenticationTokenFilter authenticationTokenFilter = new FirebaseAuthenticationTokenFilter();
    authenticationTokenFilter.setAuthenticationManager(authenticationManager());
    authenticationTokenFilter.setAuthenticationSuccessHandler((request, response, authentication) -> { });
    return authenticationTokenFilter;
  }

  @Override
  public void configure(WebSecurity web) throws Exception
  {
    web.ignoring().antMatchers(HttpMethod.OPTIONS);
  }

  @Override
  protected void configure(HttpSecurity httpSecurity) throws Exception
  {
    httpSecurity
        .cors()
        .and()
        // we don't need CSRF because our token is invulnerable
        .csrf().disable()
        // All urls must be authenticated (filter for token always fires (/**)
        .authorizeRequests()
        .antMatchers(HttpMethod.OPTIONS).permitAll()
        .antMatchers("/api/v1/**").authenticated()
        .and()
        // don't create session
        .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    // Custom JWT based security filter
    httpSecurity
        .addFilterBefore(authenticationTokenFilterBean(), UsernamePasswordAuthenticationFilter.class);

    // disable page caching
    // httpSecurity.headers().cacheControl();
  }
}