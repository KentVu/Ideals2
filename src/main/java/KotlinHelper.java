import com.intellij.openapi.fileEditor.impl.EditorComposite;
import com.intellij.openapi.fileEditor.impl.EditorCompositeModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.Flow;

public class KotlinHelper {
    @Nullable
    public static EditorComposite getEditorComposite(@NotNull VirtualFile file, @NotNull Flow<EditorCompositeModel> asFlow, @NotNull Project project, @NotNull CoroutineScope coroutineScope) {
        return new EditorComposite(file, asFlow, project, coroutineScope);
    }
}
