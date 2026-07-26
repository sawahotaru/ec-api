package com.example.ecapi.config;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.Role;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import com.example.ecapi.domain.User;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.ProductRepository;
import com.example.ecapi.repository.TaxRateRepository;
import com.example.ecapi.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds demo data on startup when app.seed.enabled=true and the DB is empty.
 * Lets the deployed API be explored immediately without manual data entry.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final TaxRateRepository taxRateRepository;
    private final PasswordEncoder passwordEncoder;

    private final boolean seedEnabled;
    private final String adminEmail;
    private final String adminPassword;

    public DataSeeder(UserRepository userRepository,
                      CategoryRepository categoryRepository,
                      ProductRepository productRepository,
                      TaxRateRepository taxRateRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.enabled}") boolean seedEnabled,
                      @Value("${app.seed.admin-email}") String adminEmail,
                      @Value("${app.seed.admin-password}") String adminPassword) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.taxRateRepository = taxRateRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        seedAdmin();
        seedDemoUser();
        seedTaxRates();
        if (categoryRepository.count() == 0 && productRepository.count() == 0) {
            seedCatalog();
        }
    }

    /** 現行の消費税率: 標準10% / 軽減8%（2019-10-01〜・無期限）。将来の改定は行を追加する。 */
    private void seedTaxRates() {
        if (taxRateRepository.count() > 0) {
            return;
        }
        LocalDate since = LocalDate.of(2019, 10, 1);
        taxRateRepository.save(taxRate(TaxCategory.STANDARD, "10.00", since));
        taxRateRepository.save(taxRate(TaxCategory.REDUCED, "8.00", since));
    }

    private TaxRate taxRate(TaxCategory category, String percent, LocalDate from) {
        TaxRate r = new TaxRate();
        r.setCategory(category);
        r.setRatePercent(new BigDecimal(percent));
        r.setEffectiveFrom(from);
        return r;
    }

    private void seedAdmin() {
        if (!userRepository.existsByEmail(adminEmail)) {
            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setName("Administrator");
            admin.setRole(Role.ADMIN);
            userRepository.save(admin);
        }
    }

    private void seedDemoUser() {
        if (!userRepository.existsByEmail("user@example.com")) {
            User user = new User();
            user.setEmail("user@example.com");
            user.setPassword(passwordEncoder.encode("user1234"));
            user.setName("Demo User");
            user.setRole(Role.USER);
            userRepository.save(user);
        }
    }

    private void seedCatalog() {
        Category apparel = category("Apparel", "apparel");
        Category electronics = category("Electronics", "electronics");
        Category books = category("Books", "books");

        product("Classic T-Shirt", "Soft cotton crew-neck tee.", "2480", 120, apparel,
                "https://picsum.photos/seed/tshirt/600/400");
        product("Denim Jacket", "Vintage-wash denim jacket.", "8900", 30, apparel,
                "https://picsum.photos/seed/denim/600/400");
        product("Wireless Earbuds", "Bluetooth 5.3, noise isolation.", "6980", 45, electronics,
                "https://picsum.photos/seed/earbuds/600/400");
        product("USB-C Charger 65W", "GaN fast charger, compact.", "3480", 80, electronics,
                "https://picsum.photos/seed/charger/600/400");
        product("Clean Code", "A handbook of agile software craftsmanship.", "4200", 25, books,
                "https://picsum.photos/seed/cleancode/600/400");
        product("The Pragmatic Programmer", "Your journey to mastery.", "3960", 18, books,
                "https://picsum.photos/seed/pragprog/600/400");
    }

    private Category category(String name, String slug) {
        Category c = new Category();
        c.setName(name);
        c.setSlug(slug);
        return categoryRepository.save(c);
    }

    private void product(String name, String desc, String price, int stock, Category category, String imageUrl) {
        Product p = new Product();
        p.setName(name);
        p.setDescription(desc);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setCategory(category);
        p.setImageUrl(imageUrl);
        productRepository.save(p);
    }
}
