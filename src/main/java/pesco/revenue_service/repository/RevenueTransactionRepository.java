package pesco.revenue_service.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pesco.revenue_service.enums.TransactionType;
import pesco.revenue_service.model.RevenueTransaction;

@Repository
public interface RevenueTransactionRepository extends JpaRepository<RevenueTransaction, Long> {
    List<RevenueTransaction> findByRevenue_Id(Long revenueId);
    
   @Query("SELECT r FROM RevenueTransaction r WHERE r.transactionType = :transactionType")
    List<RevenueTransaction> findByTransactionType(@Param("transactionType") TransactionType transactionType);


}
