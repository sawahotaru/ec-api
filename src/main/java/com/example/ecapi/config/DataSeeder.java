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

    /**
     * 和雑貨セレクトショップを想定したデモカタログ。
     *
     * <p>飲食料品（日本茶・和菓子）を <b>軽減税率8%</b>、器と雑貨を <b>標準税率10%</b> に
     * 割り当ててあるのは意図的で、1つのカートに両方が入ると税区分ごとの内訳が出る。
     * 実装済みの消費税計算（区分別・注文時スナップショット）がデモ上で目に見える。
     *
     * <p>価格は内税（{@code INCLUSIVE}）前提の税込価格。税抜がちょうど整数になる値を選んで
     * あるので、内訳表示の検算がしやすい（例: 1,620円 = 1,500円 + 8%）。
     *
     * <p>画像は外部サービスではなく同梱JPEG（`static/images/products/`・1200x800＝表示の3:2）。
     * 相対パスなのでサブパス配信（本番の `/ec/`）でもフロント側で基準URLが補われる。
     */
    private void seedCatalog() {
        Category tea = category("日本茶", "tea");
        Category sweets = category("和菓子", "wagashi");
        Category tableware = category("和食器", "tableware");
        Category crafts = category("和雑貨", "crafts");

        // --- 日本茶（軽減8%）---
        product("宇治抹茶 30g", "石臼挽きの宇治抹茶。薄茶から濃茶まで、点てて香りの立つ一番茶仕立て。",
                "1620", 60, tea, TaxCategory.REDUCED, "matcha.jpg");
        product("玉露 100g", "覆下栽培でうまみを引き出した高級煎茶。ぬるめのお湯でゆっくりと。",
                "3240", 35, tea, TaxCategory.REDUCED, "gyokuro.jpg");
        product("ほうじ茶 200g", "強火で焙じた香ばしい茶葉。カフェインが穏やかで食後や就寝前にも。",
                "1080", 90, tea, TaxCategory.REDUCED, "houjicha.jpg");

        // --- 和菓子（軽減8%）---
        product("本練羊羹 中棹", "北海道産小豆と寒天だけで炊き上げた本練羊羹。濃いめのお茶とともに。",
                "1296", 48, sweets, TaxCategory.REDUCED, "yokan.jpg");
        product("最中 詰合せ 6個", "香ばしい皮と粒餡の最中。皮と餡が別包装で、食べる直前に挟めます。",
                "1512", 40, sweets, TaxCategory.REDUCED, "monaka.jpg");

        // --- 和食器（標準10%）---
        product("藍染湯呑み 二客組", "呉須の藍が美しい波紋の湯呑み。手に馴染む大小の夫婦湯呑みです。",
                "2750", 25, tableware, TaxCategory.STANDARD, "yunomi.jpg");
        product("越前塗 汁椀", "木地に漆を重ねた汁椀。熱を伝えにくく、口当たりがやわらかです。",
                "4950", 18, tableware, TaxCategory.STANDARD, "shiruwan.jpg");
        product("若狭塗箸 一膳", "研ぎ出しの模様が一膳ごとに異なる塗り箸。先細加工でつまみやすい。",
                "1980", 70, tableware, TaxCategory.STANDARD, "hashi.jpg");

        // --- 和雑貨（標準10%）---
        product("京扇子 桜文", "職人が一本ずつ仕上げた京扇子。桜をあしらった夏の贈り物に。",
                "5500", 22, crafts, TaxCategory.STANDARD, "sensu.jpg");
        product("綿風呂敷 麻の葉 90cm", "一升瓶も包める大判サイズ。バッグにも仕立てられる綿の風呂敷。",
                "2200", 55, crafts, TaxCategory.STANDARD, "furoshiki.jpg");
        product("注染手ぬぐい 二枚組", "手ぬぐい特有の裏表のない染め。使うほどに柔らかくなります。",
                "1320", 80, crafts, TaxCategory.STANDARD, "tenugui.jpg");
        product("黒猫武将 こてつ", "三日月兜をかぶった黒猫武将の、ちょっと得意げなデスクトップ人形。",
                "3850", 30, crafts, TaxCategory.STANDARD, "kotetsu.jpg");
    }

    private Category category(String name, String slug) {
        Category c = new Category();
        c.setName(name);
        c.setSlug(slug);
        return categoryRepository.save(c);
    }

    /** @param image `static/images/products/` 配下のファイル名（相対パスで保存する） */
    private void product(String name, String desc, String price, int stock, Category category,
                         TaxCategory taxCategory, String image) {
        Product p = new Product();
        p.setName(name);
        p.setDescription(desc);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setCategory(category);
        p.setTaxCategory(taxCategory);
        p.setImageUrl("images/products/" + image);
        productRepository.save(p);
    }
}
