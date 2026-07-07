package com.example.ecapi.service;

import com.example.ecapi.domain.Category;
import com.example.ecapi.dto.CategoryDtos.CategoryRequest;
import com.example.ecapi.exception.ConflictException;
import com.example.ecapi.exception.NotFoundException;
import com.example.ecapi.repository.CategoryRepository;
import com.example.ecapi.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Category get(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found: " + id));
    }

    @Transactional
    public Category create(CategoryRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new ConflictException("Slug already used: " + request.slug());
        }
        Category category = new Category();
        category.setName(request.name());
        category.setSlug(request.slug());
        return categoryRepository.save(category);
    }

    @Transactional
    public Category update(Long id, CategoryRequest request) {
        Category category = get(id);
        if (!category.getSlug().equals(request.slug()) && categoryRepository.existsBySlug(request.slug())) {
            throw new ConflictException("Slug already used: " + request.slug());
        }
        category.setName(request.name());
        category.setSlug(request.slug());
        return categoryRepository.save(category);
    }

    @Transactional
    public void delete(Long id) {
        Category category = get(id);
        if (productRepository.existsByCategoryId(id)) {
            throw new ConflictException("Category has products and cannot be deleted: " + id);
        }
        categoryRepository.delete(category);
    }
}
