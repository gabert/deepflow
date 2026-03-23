package com.github.gabert.deepflow.demo.library.service;

import com.github.gabert.deepflow.demo.library.repository.AuthorDTO;
import com.github.gabert.deepflow.demo.library.repository.BookDTO;
import com.github.gabert.deepflow.demo.library.repository.LibraryDAO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LibraryService {

    private final LibraryDAO libraryDAO;

    public LibraryService(LibraryDAO libraryDAO) {
        this.libraryDAO = libraryDAO;
    }

    public AuthorSO createAuthor(String name) {
        AuthorDTO dto = libraryDAO.saveAuthor(name);
        return toAuthorSO(dto);
    }

    public List<AuthorSO> listAuthors() {
        return libraryDAO.findAllAuthors().stream()
                .map(this::toAuthorSO)
                .toList();
    }

    public AuthorSO findAuthor(Long id) {
        AuthorDTO dto = libraryDAO.findAuthorById(id)
                .orElseThrow(() -> new IllegalArgumentException("Author not found: " + id));
        return toAuthorSO(dto);
    }

    public BookSO addBook(Long authorId, String title, String isbn, int year) {
        BookDTO dto = libraryDAO.saveBook(authorId, title, isbn, year);
        return toBookSO(dto);
    }

    public List<BookSO> listBooks() {
        return libraryDAO.findAllBooks().stream()
                .map(this::toBookSO)
                .toList();
    }

    public List<BookSO> booksByAuthor(Long authorId) {
        return libraryDAO.findBooksByAuthorId(authorId).stream()
                .map(this::toBookSO)
                .toList();
    }

    public List<BookSO> booksByYearRange(int from, int to) {
        return libraryDAO.findBooksByYearRange(from, to).stream()
                .map(this::toBookSO)
                .toList();
    }

    public void deleteBook(Long bookId) {
        libraryDAO.deleteBook(bookId);
    }

    private AuthorSO toAuthorSO(AuthorDTO dto) {
        return new AuthorSO(dto.getId(), dto.getName());
    }

    private BookSO toBookSO(BookDTO dto) {
        return new BookSO(
                dto.getId(),
                dto.getTitle(),
                dto.getIsbn(),
                dto.getYear(),
                dto.getAuthorId(),
                dto.getAuthorName());
    }
}
