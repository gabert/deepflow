package com.github.gabert.deepflow.demo.library.service;

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

        // Step 1: Create authors
        AuthorSO tolkien = createAuthor("J.R.R. Tolkien");
        AuthorSO asimov = createAuthor("Isaac Asimov");
        AuthorSO tolkienTypo = createAuthor("J.R.R. Tolkein");  // deliberate typo
        result.put("created_authors", List.of(tolkien, asimov, tolkienTypo));

        // Step 2: Add books to authors
        BookSO hobbit = addBook(tolkien.getId(), "The Hobbit", "978-0-618-00221-3", 1937);
        BookSO lotr = addBook(tolkien.getId(), "The Lord of the Rings", "978-0-618-64015-7", 1954);
        BookSO foundation = addBook(asimov.getId(), "Foundation", "978-0-553-29335-7", 1951);
        BookSO iRobot = addBook(asimov.getId(), "I, Robot", "978-0-553-29438-5", 1950);
        BookSO silmarillion = addBook(tolkienTypo.getId(), "The Silmarillion", "978-0-618-39111-3", 1977);
        result.put("created_books", List.of(hobbit, lotr, foundation, iRobot, silmarillion));

        // Step 3: Fix the typo — rename "Tolkein" to "Tolkien"
        AuthorSO fixedAuthor = renameAuthor(tolkienTypo.getId(), "J.R.R. Tolkien (duplicate)");
        result.put("renamed_author", fixedAuthor);

        // Step 4: Merge the duplicate into the real Tolkien
        List<BookSO> mergedBooks = mergeAuthors(fixedAuthor.getId(), tolkien.getId());
        result.put("merged_tolkien_books", mergedBooks);

        // Step 5: Oops — "I, Robot" is actually a short story collection.
        //         Transfer it to a new author to simulate a correction.
        AuthorSO binder = createAuthor("Eando Binder");
        BookSO transferred = transferBook(iRobot.getId(), binder.getId());
        result.put("transferred_book", transferred);

        // Step 6: Final state
        result.put("all_authors", listAuthors());
        result.put("all_books", listBooks());

        return result;
    }

    public AuthorSO renameAuthor(Long authorId, String newName) {
        AuthorDTO dto = libraryDAO.renameAuthor(authorId, newName);
        return toAuthorSO(dto);
    }

    public BookSO transferBook(Long bookId, Long toAuthorId) {
        BookDTO dto = libraryDAO.transferBook(bookId, toAuthorId);
        return toBookSO(dto);
    }

    public List<BookSO> mergeAuthors(Long sourceAuthorId, Long targetAuthorId) {
        return libraryDAO.mergeAuthors(sourceAuthorId, targetAuthorId).stream()
                .map(this::toBookSO)
                .toList();
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
