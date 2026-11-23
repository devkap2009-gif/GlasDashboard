package de.glas.dashboard

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Startet die echten Bildschirme in einer simulierten Android-Umgebung, um Abstürze zu finden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiSmokeTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun mainActivityStarts() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @Test fun pagesBuild() {
        val ui = Ui(ctx)
        val pages = listOf(
            StrategyPage(ui), LogicPage(ui), ImplPage(ui), GamesPage(ui),
            NotesPage(ui) {}, ProfilePage(ui, { emptyList() }, {}, {}),
            StartPage(ui, {}, {})
        )
        pages.forEach { it.onShow(); it.onHide() }
    }

    @Test fun everyGameAndToolOpens() {
        for (i in 0..2) { Prefs.setInt(ctx, "game_sel", i); GamesPage(Ui(ctx)).onShow() }
        for (i in 0..4) { Prefs.setInt(ctx, "strat_tool", i); StrategyPage(Ui(ctx)).onShow() }
        for (i in 0..2) { Prefs.setInt(ctx, "logic_mode", i); LogicPage(Ui(ctx)).onShow() }
    }

    @Test fun panelOpensAndCloses() {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val p = DashboardPanel(ctx, wm)
        p.drag(0.5f)
        p.open()
        p.openQuickNote()
        p.closeEditor()
        p.openAt("strategy")
        p.destroy()
    }
}
