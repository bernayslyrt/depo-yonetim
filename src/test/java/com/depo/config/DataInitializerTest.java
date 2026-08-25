package com.depo.config;

import com.depo.entity.User;
import com.depo.enums.Role;
import com.depo.repository.CategoryRepository;
import com.depo.repository.DataMigrationRepository;
import com.depo.repository.IslemGecmisiRepository;
import com.depo.repository.ProductRepository;
import com.depo.repository.StockMovementRepository;
import com.depo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private DataMigrationRepository dataMigrationRepository;
    @Mock
    private IslemGecmisiRepository islemGecmisiRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private DataInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new DataInitializer(userRepository, passwordEncoder);
    }

    @Test
    void startupDoesNotDeleteExistingProducts() {
        skipExistingDemoUsers();

        initializer.run();

        verifyNoInteractions(productRepository, dataMigrationRepository);
    }

    @Test
    void startupDoesNotDeleteExistingCategories() {
        skipExistingDemoUsers();

        initializer.run();

        verifyNoInteractions(categoryRepository);
    }

    @Test
    void startupDoesNotDeleteStockMovementsOrTransactionHistory() {
        skipExistingDemoUsers();

        initializer.run();

        verifyNoInteractions(stockMovementRepository, islemGecmisiRepository);
    }

    @Test
    void createsDemoUsersInLocalProfile() {
        Profile profile = DataInitializer.class.getAnnotation(Profile.class);
        assertNotNull(profile);
        assertArrayEquals(new String[]{"local"}, profile.value());

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation ->
                "$2a$10$test-hash-for-" + invocation.getArgument(0, String.class));

        initializer.run();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(13)).save(userCaptor.capture());
        verify(passwordEncoder, times(13)).encode(anyString());

        List<User> users = userCaptor.getAllValues();
        assertEquals(13, users.size());
        assertEquals(1, users.stream().filter(user -> user.getRole() == Role.ADMIN).count());
        assertEquals("demo-user-06", users.stream()
                .filter(user -> user.getRole() == Role.ADMIN)
                .findFirst()
                .orElseThrow()
                .getUsername());
        assertEquals(12, users.stream().filter(user -> user.getRole() == Role.USER).count());
        assertFalse(users.stream().anyMatch(user -> !user.getPassword().startsWith("$2a$")));
        verifyNoInteractions(
                dataMigrationRepository,
                islemGecmisiRepository,
                stockMovementRepository,
                productRepository,
                categoryRepository
        );
    }

    @Test
    void skipsExistingDemoUsersOnSubsequentStarts() {
        skipExistingDemoUsers();

        initializer.run();

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
        verifyNoInteractions(
                dataMigrationRepository,
                islemGecmisiRepository,
                stockMovementRepository,
                productRepository,
                categoryRepository
        );
    }

    private void skipExistingDemoUsers() {
        when(userRepository.existsByUsername(anyString())).thenReturn(true);
    }
}
