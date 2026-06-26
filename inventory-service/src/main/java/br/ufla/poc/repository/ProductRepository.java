package br.ufla.poc.repository;
import br.ufla.poc.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProductRepository extends JpaRepository<Product, String> {}
