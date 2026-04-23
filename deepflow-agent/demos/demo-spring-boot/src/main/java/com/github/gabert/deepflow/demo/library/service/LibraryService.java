package com.github.gabert.deepflow.demo.library.service;

import com.github.gabert.deepflow.demo.library.model.AuthorEntity;
import com.github.gabert.deepflow.demo.library.repository.AuthorDTO;
import com.github.gabert.deepflow.demo.library.repository.BookDTO;
import com.github.gabert.deepflow.demo.library.repository.LibraryDAO;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public Map<String, Object> runDemoScenario() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Step 1: Create an author with three books
        AuthorSO tolkien = createAuthor("J.R.R. Tolkien");
        BookSO hobbit = addBook(tolkien.getId(), "The Hobbit", "978-0-618-00221-3", 1937);
        BookSO lotr = addBook(tolkien.getId(), "The Lord of the Rings", "978-0-618-64015-7", 1954);
        BookSO silmarillion = addBook(tolkien.getId(), "The Silmarillion", "978-0-618-39111-3", 1977);
        result.put("created_books", List.of(hobbit, lotr, silmarillion));

        // Step 2: "Prepare catalog export" — looks read-only, but isn't
        Map<String, Object> catalogExport = prepareCatalogExport(tolkien.getId());
        result.put("catalog_export", catalogExport);

        // Step 3: Read the author back — surprise! data is corrupted
        //         The "read-only" export silently mutated the JPA entities
        AuthorSO afterExport = findAuthor(tolkien.getId());
        List<BookSO> booksAfterExport = booksByAuthor(tolkien.getId());
        result.put("author_after_export", afterExport);
        result.put("books_after_export", booksAfterExport);

        return result;
    }

    public Map<String, Object> prepareCatalogExport(Long authorId) {
        Map<String, Object> export = new LinkedHashMap<>();

        // Load the JPA entity — this is the managed instance
        AuthorEntity author = libraryDAO.findAuthorEntityById(authorId);
        export.put("original_author", author.getName());

        // "Normalize ISBNs for export" — BUG: mutates the entities
        String isbnReport = libraryDAO.normalizeIsbns(author);
        export.put("isbn_normalization", isbnReport);

        // "Build display name for export" — BUG: mutates the entity
        String displayName = libraryDAO.buildDisplayName(author);
        export.put("display_name", displayName);

        // Read back the books — they already have mutated ISBNs
        List<BookSO> booksSnapshot = libraryDAO.findBooksByAuthorEntity(author).stream()
                .map(this::toBookSO)
                .toList();
        export.put("books_snapshot", booksSnapshot);

        // Flush to DB — the accidental mutations are now persisted
        AuthorDTO flushed = libraryDAO.flushAndReload(authorId);
        export.put("flushed_author", toAuthorSO(flushed));

        return export;
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
