package com.dodaso.ecosystem.ai.config;

import com.dodaso.ecosystem.ecws.listener.AuditEventListener;
import java.util.Collections;
import java.util.List;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HibernateConfig {

  @Bean
  public HibernatePropertiesCustomizer hibernatePropertiesCustomizer() {
    return hibernateProperties -> {
      hibernateProperties.put("hibernate.integrator_provider",
          new AuditEventListenerIntegratorProvider());
      hibernateProperties.put("hibernate.type.preferred_vector_type", "pgvector");
    };
  }

  public static class AuditEventListenerIntegratorProvider implements IntegratorProvider {

    @Override
    public List<Integrator> getIntegrators() {
      return Collections.singletonList(new AuditEventListenerIntegrator());
    }
  }

  public static class AuditEventListenerIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory,
        SessionFactoryServiceRegistry serviceRegistry) {

      EventListenerRegistry eventListenerRegistry =
          serviceRegistry.getService(EventListenerRegistry.class);
      assert eventListenerRegistry != null;
      eventListenerRegistry.appendListeners(EventType.PRE_UPDATE, new AuditEventListener());
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
        SessionFactoryServiceRegistry serviceRegistry) {
      // Nothing to do
    }
  }
}