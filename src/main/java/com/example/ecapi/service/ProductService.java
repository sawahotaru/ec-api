package com.example.ecapi.service;

import com.example.ecapi.domain.Category;
import com.example.ecapi.domain.Product;
import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.dto.ProductDtos.ProductRequest;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.media.ProductImageStorage;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.ProductRepository;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageStorage imageStorage;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          ProductImageStorage imageStorage) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.imageStorage = imageStorage;
    }

    @Transactional(readOnly = true)
    public Page<Product> search(String q, Long categoryId, Pageable pageable) {
        String query = StringUtils.hasText(q) ? q : null;
        return productRepository.search(query, categoryId, pageable);
    }

    @Transactional(readOnly = true)
    public Product get(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
    }

    @Transactional
    public Product create(ProductRequest request) {
        Product product = new Product();
        apply(product, request);
        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, ProductRequest request) {
        Product product = get(id);
        String previousImage = product.getImageUrl();
        apply(product, request);
        Product saved = productRepository.save(product);
        // Editing a product can point imageUrl somewhere else; the file it used to
        // point at is then unreachable. Drop it so uploads/ does not grow forever.
        if (!Objects.equals(previousImage, saved.getImageUrl())) {
            imageStorage.deleteIfUploaded(previousImage);
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Product product = get(id);
        String imageUrl = product.getImageUrl();
        productRepository.delete(product);
        imageStorage.deleteIfUploaded(imageUrl);
    }

    /**
     * Stores an uploaded image and points the product at it.
     *
     * <p>The file is written first and the row updated second: a failed upload must not
     * leave the product pointing at something that is not there. The reverse order —
     * an orphaned file after a failed save — costs disk space and nothing else.
     */
    @Transactional
    public Product setImage(Long id, MultipartFile file) {
        Product product = get(id);
        String previousImage = product.getImageUrl();
        product.setImageUrl(imageStorage.store(file, product.getId()));
        Product saved = productRepository.save(product);
        imageStorage.deleteIfUploaded(previousImage);
        return saved;
    }

    /** Clears the product's image, deleting the file if we were the ones who stored it. */
    @Transactional
    public Product clearImage(Long id) {
        Product product = get(id);
        String previousImage = product.getImageUrl();
        product.setImageUrl(null);
        Product saved = productRepository.save(product);
        imageStorage.deleteIfUploaded(previousImage);
        return saved;
    }

    private void apply(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setImageUrl(request.imageUrl());
        product.setTaxCategory(request.taxCategory() != null ? request.taxCategory() : TaxCategory.STANDARD);
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new NotFoundException("Category not found: " + request.categoryId()));
            product.setCategory(category);
        } else {
            product.setCategory(null);
        }
    }
}
