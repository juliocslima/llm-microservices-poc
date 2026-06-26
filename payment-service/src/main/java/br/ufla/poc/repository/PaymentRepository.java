package br.ufla.poc.repository;

import br.ufla.poc.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {}
