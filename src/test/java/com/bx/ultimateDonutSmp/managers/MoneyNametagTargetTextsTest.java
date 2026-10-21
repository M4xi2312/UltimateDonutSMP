package com.bx.ultimateDonutSmp.managers;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyNametagTargetTextsTest {

    @Test
    void eachTargetIsRenderedOnceHoweverManyViewersAsk() {
        Player alice = player(UUID.randomUUID(), "Alice");
        Player bob = player(UUID.randomUUID(), "Bob");
        Map<String, Integer> renders = new HashMap<>();

        MoneyNametagManager.TargetTexts texts = new MoneyNametagManager.TargetTexts(target -> {
            renders.merge(target.getName(), 1, Integer::sum);
            return "$ " + target.getName();
        });

        for (int viewer = 0; viewer < 5; viewer++) {
            assertEquals("$ Alice", texts.textFor(alice));
            assertEquals("$ Bob", texts.textFor(bob));
        }

        assertEquals(1, renders.get("Alice"));
        assertEquals(1, renders.get("Bob"));
    }

    @Test
    void aNewPassRendersAgain() {
        Player alice = player(UUID.randomUUID(), "Alice");
        int[] renders = {0};

        new MoneyNametagManager.TargetTexts(target -> "#" + ++renders[0]).textFor(alice);
        String second = new MoneyNametagManager.TargetTexts(target -> "#" + ++renders[0]).textFor(alice);

        assertEquals("#2", second);
    }

    private static Player player(UUID uuid, String name) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
