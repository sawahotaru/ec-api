package com.example.ecapi.controller;

import com.example.ecapi.dto.ProductDtos.ProductRequest;
import com.example.ecapi.dto.ProductDtos.ProductResponse;
import com.example.ecapi.media.ProductImageStorage;
import com.example.ecapi.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Admin: Products", description = "Product management (ADMIN only)")
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;
    private final ProductImageStorage imageStorage;

    public AdminProductController(ProductService productService, ProductImageStorage imageStorage) {
        this.productService = productService;
        this.imageStorage = imageStorage;
    }

    @Operation(summary = "Create a product")
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductResponse.from(productService.create(request)));
    }

    @Operation(summary = "Update a product")
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(ProductResponse.from(productService.update(id, request)));
    }

    @Operation(summary = "Delete a product")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Upload a product image",
            description = "multipart/form-data の `file`。JPEG / PNG / WebP のみ（拡張子や "
                    + "Content-Type ではなく先頭バイトで判定）。成功すると imageUrl が "
                    + "images/uploads/... を指す。")
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductResponse> uploadImage(@PathVariable Long id,
                                                       @RequestParam("file") MultipartFile file) {
        if (!imageStorage.isAvailable()) {
            // The upload directory could not be created or is read-only. Say so plainly
            // instead of failing per-request with a 500 that looks like a bug in the upload.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(ProductResponse.from(productService.setImage(id, file)));
    }

    @Operation(summary = "Remove a product image",
            description = "imageUrl を空にする。アップロードした実体ファイルも消える"
                    + "（同梱画像・外部URLの場合はファイルには触れない）。")
    @DeleteMapping("/{id}/image")
    public ResponseEntity<ProductResponse> deleteImage(@PathVariable Long id) {
        return ResponseEntity.ok(ProductResponse.from(productService.clearImage(id)));
    }
}
