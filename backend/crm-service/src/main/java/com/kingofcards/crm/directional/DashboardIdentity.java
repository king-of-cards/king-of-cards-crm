package com.kingofcards.crm.directional;
import java.util.UUID;
import com.kingofcards.crm.directional.DirectionalModels.Vendor;

@FunctionalInterface
public interface DashboardIdentity { Vendor vendor(UUID subject,boolean admin,String verifiedToken); }
