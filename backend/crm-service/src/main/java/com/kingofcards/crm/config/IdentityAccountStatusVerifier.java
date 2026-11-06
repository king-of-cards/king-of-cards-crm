package com.kingofcards.crm.config;
import java.util.*;
import com.kingofcards.crm.exception.LeadApiException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.*;

/** CRM still validates JWT cryptography; Identity independently confirms current account state. */
public final class IdentityAccountStatusVerifier implements AccountStatusVerifier {
    private final RestClient client;private final String base;
    public IdentityAccountStatusVerifier(RestClient client){this(client,"http://identity-service");}
    public IdentityAccountStatusVerifier(RestClient client,String base){
        if(!base.equals("http://identity-service") && !base.equals("http://127.0.0.1:18081"))throw new IllegalArgumentException("Identity endpoint is not allowlisted.");
        this.client=client;this.base=base;
    }
    public void verify(Jwt jwt) {
        UUID id;List<String> roles;
        try{id=UUID.fromString(jwt.getSubject());roles=jwt.getClaimAsStringList("roles");
            if(!id.toString().equals(jwt.getSubject()) || roles==null || roles.size()!=1 || !Set.of("CRM_ADMIN","CRM_VENDOR").contains(roles.getFirst()))throw denied();
        }catch(RuntimeException exception){throw denied();}
        try{
            var account=client.get().uri(base+"/auth/me").headers(headers->headers.setBearerAuth(jwt.getTokenValue()))
                .retrieve().body(CurrentAccount.class);
            if(account==null)throw LeadApiException.unavailable();
            if(!id.equals(account.id()) || !roles.equals(account.roles()))throw denied();
        }catch(RestClientResponseException exception){
            if(exception.getStatusCode().value()==401 || exception.getStatusCode().value()==403)throw denied();
            throw LeadApiException.unavailable();
        }catch(RestClientException exception){throw LeadApiException.unavailable();}
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("Account access is not active for this token.");}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record CurrentAccount(UUID id,List<String> roles){}
}
