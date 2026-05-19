package myau.module;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.KeyEvent;
import myau.events.TickEvent;
import myau.module.modules.GuiModule;
import myau.module.modules.HUD;
import myau.util.ChatUtil;
import myau.util.KeyBindUtil;
import myau.util.SoundUtil;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

public class ModuleManager {
    private boolean sound = false;
    // Tracks keys that are already held so holding a bind does not spam toggles.
    private final Set<Integer> pressedKeys = new HashSet<>();
    public final LinkedHashMap<Class<?>, Module> modules = new LinkedHashMap<>();

    public Module getModule(String string) {
        return this.modules.values().stream().filter(mD -> mD.getName().equalsIgnoreCase(string)).findFirst().orElse(null);
    }

    public Module getModule(Class<?> clazz){
        return this.modules.get(clazz);
    }

    public void playSound() {
        this.sound = true;
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        // Module binds are handled in pollKeyBinds now. runTick key events can arrive late at 0.01x Timer
        // and would replay old toggles after Timer is disabled.
    }

    public void pollKeyBinds(boolean allowToggle) {
        // This is polled from runGameLoop so keybinds still respond when Timer slows game ticks to 0.01x.
        for (Module module : this.modules.values()) {
            int key = module.getKey();
            if (key == 0) {
                continue;
            }

            boolean pressed = KeyBindUtil.isKeyDown(key);
            if (!pressed) {
                this.pressedKeys.remove(key);
            } else if (allowToggle && this.pressedKeys.add(key)) {
                this.handleKeyPress(key);
            }
        }
    }

    private void handleKeyPress(int key) {
        for (Module module : this.modules.values()) {
            if (module.getKey() != key) {
                continue;
            }
            boolean shouldNotify = module.toggle();
            HUD hud = (HUD) this.modules.get(HUD.class);
            if (hud != null && shouldNotify) {
                shouldNotify = hud.toggleAlerts.getValue();
            }
            if(module instanceof GuiModule){
                shouldNotify = false;
            }
            if (shouldNotify) {
                String status = module.isEnabled() ? "&a&lON" : "&c&lOFF";
                String message = String.format("%s%s: %s&r", Myau.clientName, module.getName(), status);
                ChatUtil.sendFormatted(message);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            if (this.sound) {
                this.sound = false;
                SoundUtil.playSound("random.click");
            }
        }
    }
}
