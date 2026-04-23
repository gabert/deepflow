package com.github.gabert.deepflow.demo.library.repository;

import com.github.gabert.deepflow.demo.library.model.AuthorEntity;
import com.github.gabert.deepflow.demo.library.model.BookEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class LibraryDAO {

    private final AuthorRepository authorRepository;
    private final BookRepository bookRepository;

    public LibraryDAO(AuthorRepository authorRepository, BookRepository bookRepository) {
        this.authorRepository = authorRepository;
        this.bookRepository = bookRepository;
    }

    @Transactional
    public AuthorDTO saveAuthor(String name) {
        AuthorEntity entity = new AuthorEntity();
        entity.setName(name);
        entity = authorRepository.save(entity);
        return toAuthorDTO(entity);
    }

    public List<AuthorDTO> findAllAuthors() {
        return authorRepository.findAll().stream()
                .map(this::toAuthorDTO)
                .toList();
    }

    public Optional<AuthorDTO> findAuthorById(Long id) {
        return authorRepository.findById(id)
                .map(this::toAuthorDTO);
    }

    @Transactional
    public BookDTO saveBook(Long authorId, String title, String isbn, int year) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("Author not found: " + authorId));
        BookEntity entity = new BookEntity();
        entity.setTitle(title);
        entity.setIsbn(isbn);
        entity.setYear(year);
        entity.setAuthor(author);
        author.getBooks().add(entity);
        entity = bookRepository.save(entity);
        return toBookDTO(entity);
    }

    public List<BookDTO> findAllBooks() {
        return bookRepository.findAll().stream()
                .map(this::toBookDTO)
                .toList();
    }

    public List<BookDTO> findBooksByAuthorId(Long authorId) {
        return bookRepository.findByAuthorId(authorId).stream()
                .map(this::toBookDTO)
                .toList();
    }

    public List<BookDTO> findBooksByYearRange(int from, int to) {
        return bookRepository.findByYearBetween(from, to).stream()
                .map(this::toBookDTO)
                .toList();
    }

    @Transactional
    public void deleteBook(Long bookId) {
        BookEntity entity = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));
        entity.getAuthor().getBooks().remove(entity);
        bookRepository.delete(entity);
    }

    public AuthorEntity findAuthorEntityById(Long id) {
        return authorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Author not found: " + id));
    }

    public String normalizeIsbns(AuthorEntity author) {
        StringBuilder report = new StringBuilder();
        for (BookEntity book : author.getBooks()) {
            String original = book.getIsbn();
            // BUG: modifies the entity field directly instead of building
            //      a separate export string — the JPA-managed entity is
            //      now dirty and will be flushed on next transaction
            book.setIsbn(original.replace("-", ""));
            report.append(book.getTitle())
                  .append(": ")
                  .append(original)
                  .append(" -> ")
                  .append(book.getIsbn())
                  .append("\n");
        }
        return report.toString();
    }

    public String buildDisplayName(AuthorEntity author) {
        String fullName = author.getName();
        // BUG: writes the reformatted name back to the entity
        //      instead of returning it as a separate string
        String[] parts = fullName.split(" ");
        String displayName = parts[parts.length - 1] + ", "
                + fullName.substring(0, fullName.lastIndexOf(" "));
        author.setName(displayName);
        return displayName;
    }

    @Transactional
    public AuthorDTO flushAndReload(Long authorId) {
        // Forces JPA to flush the dirty entities, persisting the
        // accidental mutations from normalizeIsbns / buildDisplayName
        authorRepository.flush();
        AuthorEntity fresh = authorRepository.findById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("Author not found: " + authorId));
        return toAuthorDTO(fresh);
    }

    public List<BookDTO> findBooksByAuthorEntity(AuthorEntity author) {
        return author.getBooks().stream()
                .map(this::toBookDTO)
                .toList();
    }

    private AuthorDTO toAuthorDTO(AuthorEntity entity) {
        return new AuthorDTO(entity.getId(), entity.getName());
    }

    private BookDTO toBookDTO(BookEntity entity) {
        return new BookDTO(
                entity.getId(),
                entity.getTitle(),
                entity.getIsbn(),
                entity.getYear(),
                entity.getAuthor().getId(),
                entity.getAuthor().getName());
    }
}
