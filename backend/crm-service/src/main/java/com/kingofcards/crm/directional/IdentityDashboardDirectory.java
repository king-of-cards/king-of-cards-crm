package com.kingofcards.crm.directional;
import java.util.*;
import com.kingofcards.crm.directional.DirectionalModels.Vendor;
import com.kingofcards.crm.exception.LeadApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.*;

public final class IdentityDashboardDirectory implements DashboardIdentity {
    private final RestClient client;private final String base;
    public IdentityDashboardDirectory(RestClient client,String base){this.client=client;this.base=base;}
    public Vendor vendor(UUID subject,boolean admin,String token){try{
        var data=client.get().uri(admin?base+"/admin/vendors/accounts/{id}":base+"/auth/vendor-profile",admin?new Object[]{subject}:new Object[]{})
            .headers(h->h.setBearerAuth(token)).retrieve().body(Vendor.class);
        if(data==null || !subject.equals(data.subjectId()) || !List.of("CRM_VENDOR").equals(data.roles()))throw new LeadApiException(HttpStatus.NOT_FOUND,"VENDOR_NOT_FOUND","Vendor was not found.");
        return data;
    }catch(RestClientResponseException exception){int code=exception.getStatusCode().value();if(code==401 || code==403)throw new org.springframework.security.access.AccessDeniedException("Vendor identity is not accessible.");if(code==404){if(!admin)throw new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE,"IDENTITY_VENDOR_PROFILE_UNAVAILABLE","The running Identity Service does not provide the required Vendor self-profile endpoint.");throw new LeadApiException(HttpStatus.NOT_FOUND,"VENDOR_NOT_FOUND","Vendor was not found.");}throw LeadApiException.unavailable();}
    catch(RestClientException exception){throw LeadApiException.unavailable();}}
}
