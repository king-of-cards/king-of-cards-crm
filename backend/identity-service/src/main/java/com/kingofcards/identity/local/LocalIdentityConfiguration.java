package com.kingofcards.identity.local;
import java.time.Clock;
import java.util.UUID;
import com.google.cloud.bigquery.BigQuery;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.service.VendorAccountDirectoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods=false)
@Profile("lead-dashboard-local")
public class LocalIdentityConfiguration {
    @Bean static BeanFactoryPostProcessor rejectProductionLocalMix(Environment env){return factory->{
        if(env.getProperty("identity.bigquery.enabled",Boolean.class,false) || env.getProperty("identity.bigquery.production-mutations-enabled",Boolean.class,false)
            || !"127.0.0.1".equals(env.getProperty("server.address")) || !"urn:koc:lead-dashboard-local".equals(env.getProperty("identity.jwt.issuer"))
            || !"koc-lead-dashboard-local".equals(env.getProperty("identity.jwt.audience")) || env.getProperty("eureka.client.enabled",Boolean.class,true))
            throw new IllegalStateException("Local Identity must use loopback, isolated JWT claims, no Eureka and no BigQuery. Refusing production/local mixing.");
    };}
    @Bean @Primary LocalIdentityStore localIdentityStore(PasswordEncoder passwords,Clock clock,
            @Value("${KOC_LOCAL_ADMIN_PASSWORD}")String admin,@Value("${KOC_LOCAL_VENDOR_PASSWORD}")String vendor){return new LocalIdentityStore(passwords,clock,admin,vendor);}
    @Bean @Primary VendorAccountDirectoryService localAccountDirectory(ObjectProvider<BigQuery> clients,IdentityBigQueryProperties properties,LocalIdentityStore users){return new LocalDirectory(clients,properties,users);}
    public static class LocalDirectory extends VendorAccountDirectoryService {
        private final LocalIdentityStore users;
        public LocalDirectory(ObjectProvider<BigQuery> clients,IdentityBigQueryProperties properties,LocalIdentityStore users){super(clients,properties,users);this.users=users;}
        @Override public VendorPageResponse list(int page,int size){return users.accounts("CRM_VENDOR",page,size);}
        @Override public VendorPageResponse listAdministrators(int page,int size){return users.accounts("CRM_ADMIN",page,size);}
        @Override public VendorResponse get(UUID id){return users.account(id,"CRM_VENDOR");}
        @Override public VendorResponse getAdministrator(UUID id){return users.account(id,"CRM_ADMIN");}
    }
}
