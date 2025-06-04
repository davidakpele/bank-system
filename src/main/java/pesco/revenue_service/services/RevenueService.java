package pesco.revenue_service.services;

import java.util.Map;
import pesco.revenue_service.model.Revenue;

public interface RevenueService {

    Revenue addRevenue(Map<String, Object> payload);
}
