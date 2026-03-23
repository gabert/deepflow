package com.github.gabert.deepflow.demo.library.controller;

import com.github.gabert.deepflow.demo.library.service.AuthorSO;
import com.github.gabert.deepflow.demo.library.service.BookSO;
import com.github.gabert.deepflow.demo.library.service.LibraryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @PostMapping("/authors")
    public AuthorSO createAuthor(@RequestParam String name) {
        return libraryService.createAuthor(name);
    }

    @GetMapping("/authors")
    public List<AuthorSO> listAuthors() {
        return libraryService.listAuthors();
    }

    @GetMapping("/authors/{id}")
    public AuthorSO getAuthor(@PathVariable Long id) {
        return libraryService.findAuthor(id);
    }

    @PostMapping("/authors/{authorId}/books")
    public BookSO addBook(@PathVariable Long authorId,
                          @RequestParam String title,
                          @RequestParam String isbn,
                          @RequestParam int year) {
        return libraryService.addBook(authorId, title, isbn, year);
    }

    @GetMapping("/books")
    public List<BookSO> listBooks(@RequestParam(required = false) Integer fromYear,
                                  @RequestParam(required = false) Integer toYear) {
        if (fromYear != null && toYear != null) {
            return libraryService.booksByYearRange(fromYear, toYear);
        }
        return libraryService.listBooks();
    }

    @GetMapping("/authors/{authorId}/books")
    public List<BookSO> booksByAuthor(@PathVariable Long authorId) {
        return libraryService.booksByAuthor(authorId);
    }

    @DeleteMapping("/books/{id}")
    public ResponseEntity<Void> deleteBook(@PathVariable Long id) {
        libraryService.deleteBook(id);
        return ResponseEntity.noContent().build();
    }
}
