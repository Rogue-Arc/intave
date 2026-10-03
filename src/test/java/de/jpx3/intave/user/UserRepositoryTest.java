/*
 * Copyright 2026 Intave
 *
 * This software is licensed under the PolyForm Perimeter License 1.0.0.
 * You may use this software for any purpose, except for providing to
 * others any product that competes with the software.
 *
 * A copy of the license is available at:
 *   https://polyformproject.org/licenses/perimeter/1.0.0/
 */

package de.jpx3.intave.user;

import de.jpx3.intave.adapter.MinecraftVersion;
import de.jpx3.intave.adapter.MinecraftVersions;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRepositoryTest {

  @Test
  void resolvesAndRemovesUsersThroughAnEqualUuidInstance() {
    MinecraftVersion.setCurrent(MinecraftVersions.VER26_3);
    UUID storedId = UUID.randomUUID();
    UUID equalId = new UUID(storedId.getMostSignificantBits(), storedId.getLeastSignificantBits());
    AtomicInteger unregisterCalls = new AtomicInteger();
    User user = user(unregisterCalls);
    Player player = player(storedId);

    UserRepository.manuallyRegisterUser(player, user);
    try {
      assertSame(user, UserRepository.userOf(equalId));
      assertTrue(UserRepository.hasUser(player));
      AtomicReference<User> visited = new AtomicReference<>();
      UserRepository.applyOnAll(candidate -> {
        if (candidate == user) {
          visited.set(candidate);
        }
      });
      assertSame(user, visited.get());
    } finally {
      UserRepository.unregisterUser(player);
    }

    assertFalse(UserRepository.hasUser(player));
    assertSame(UserRepository.fallback(), UserRepository.userOf(equalId));
    assertEquals(1, unregisterCalls.get());
  }

  private static Player player(UUID id) {
    return (Player) Proxy.newProxyInstance(
      Player.class.getClassLoader(),
      new Class<?>[]{Player.class},
      (proxy, method, arguments) -> method.getName().equals("getUniqueId") ? id : null
    );
  }

  private static User user(AtomicInteger unregisterCalls) {
    return (User) Proxy.newProxyInstance(
      User.class.getClassLoader(),
      new Class<?>[]{User.class},
      (proxy, method, arguments) -> {
        if (method.getName().equals("unregister")) {
          unregisterCalls.incrementAndGet();
        }
        return null;
      }
    );
  }
}
