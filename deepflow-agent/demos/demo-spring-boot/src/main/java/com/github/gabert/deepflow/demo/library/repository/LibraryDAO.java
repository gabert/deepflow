package com.github.gabert.deepflow.demo.library.repository;

import com.github.gabert.deepflow.demo.library.model.AuthorEntity;
import com.github.gabert.deepflow.demo.library.model.BookEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
