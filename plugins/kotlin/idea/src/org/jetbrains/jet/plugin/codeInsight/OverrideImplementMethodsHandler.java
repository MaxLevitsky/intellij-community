package org.jetbrains.jet.plugin.codeInsight;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.ide.util.MemberChooser;
import com.intellij.lang.LanguageCodeInsightActionHandler;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.types.JetStandardLibrary;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.plugin.compiler.WholeProjectAnalyzerFacade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @author yole
 */
public abstract class OverrideImplementMethodsHandler implements LanguageCodeInsightActionHandler {
    public static List<DescriptorClassMember> membersFromDescriptors(Iterable<CallableMemberDescriptor> missingImplementations) {
        List<DescriptorClassMember> members = new ArrayList<DescriptorClassMember>();
        for (CallableMemberDescriptor memberDescriptor : missingImplementations) {
            members.add(new DescriptorClassMember(memberDescriptor));
        }
        return members;
    }

    public Set<CallableMemberDescriptor> collectMethodsToGenerate(JetClassOrObject classOrObject) {
        BindingContext bindingContext = WholeProjectAnalyzerFacade.analyzeProjectWithCacheOnAFile((JetFile) classOrObject.getContainingFile());
        final DeclarationDescriptor descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, classOrObject);
        if (descriptor instanceof MutableClassDescriptor) {
            return collectMethodsToGenerate((MutableClassDescriptor) descriptor);
        }
        return Collections.emptySet();
    }

    protected abstract Set<CallableMemberDescriptor> collectMethodsToGenerate(MutableClassDescriptor descriptor);

    public static void generateMethods(Project project, Editor editor, JetClassOrObject classOrObject, List<DescriptorClassMember> selectedElements) {
        final JetClassBody body = classOrObject.getBody();
        if (body == null) {
            return;
        }

        final PsiElement newLineWhitespace = JetPsiFactory.createWhiteSpace(body.getProject(), "\n");

        for (DescriptorClassMember selectedElement : selectedElements) {

            // TODO: Insert spaces should be done by formatter
            body.addBefore(newLineWhitespace, body.getRBrace());

            final DeclarationDescriptor descriptor = selectedElement.getDescriptor();
            if (descriptor instanceof NamedFunctionDescriptor) {
                JetElement target = overrideFunction(project, (NamedFunctionDescriptor) descriptor);
                body.addBefore(target, body.getRBrace());
            }
            else if (descriptor instanceof PropertyDescriptor) {
                JetElement target = overrideProperty(project, (PropertyDescriptor) descriptor);
                body.addBefore(target, body.getRBrace());
            }
        }
    }

    private static JetElement overrideProperty(Project project, PropertyDescriptor descriptor) {
        StringBuilder bodyBuilder = new StringBuilder("override ");
        if (descriptor.isVar()) {
            bodyBuilder.append("var ");
        }
        else {
            bodyBuilder.append("val ");
        }
        bodyBuilder.append(descriptor.getName()).append(": ").append(descriptor.getOutType());
        String initializer = defaultInitializer(descriptor.getOutType(), JetStandardLibrary.getJetStandardLibrary(project));
        if (initializer != null) {
            bodyBuilder.append("=").append(initializer);
        }
        else {
            bodyBuilder.append("= ?");
        }
        return JetPsiFactory.createProperty(project, bodyBuilder.toString());
    }

    private static JetElement overrideFunction(Project project, NamedFunctionDescriptor descriptor) {
        StringBuilder bodyBuilder = new StringBuilder("override fun ");
        bodyBuilder.append(descriptor.getName());
        bodyBuilder.append("(");
        boolean first = true;
        for (ValueParameterDescriptor parameterDescriptor : descriptor.getValueParameters()) {
            if (!first) {
                bodyBuilder.append(",");
            }
            first = false;
            bodyBuilder.append(parameterDescriptor.getName());
            bodyBuilder.append(": ");
            bodyBuilder.append(parameterDescriptor.getOutType().toString());
        }
        bodyBuilder.append(")");
        final JetType returnType = descriptor.getReturnType();
        final JetStandardLibrary stdlib = JetStandardLibrary.getJetStandardLibrary(project);
        if (!returnType.equals(stdlib.getTuple0Type())) {
            bodyBuilder.append(": ").append(returnType.toString());
        }

        final String initializer = defaultInitializer(returnType, stdlib);
        if (initializer != null) {
            bodyBuilder.append(" = ").append(initializer);
        }
        else {
            bodyBuilder.append("{").append("throw UnsupportedOperationException()").append("}");
        }

        return JetPsiFactory.createFunction(project, bodyBuilder.toString());
    }

    private static String defaultInitializer(JetType returnType, JetStandardLibrary stdlib) {
        if (returnType.isNullable()) {
            return "null";
        }
        else if (returnType.equals(stdlib.getIntType()) || returnType.equals(stdlib.getLongType()) ||
                 returnType.equals(stdlib.getShortType()) || returnType.equals(stdlib.getByteType()) ||
                 returnType.equals(stdlib.getFloatType()) || returnType.equals(stdlib.getDoubleType())) {
            return "0";
        }
        else if (returnType.equals(stdlib.getBooleanType())) {
            return "false";
        }
        return null;
    }

    private MemberChooser<DescriptorClassMember> showOverrideImplementChooser(Project project,
                                                                                     DescriptorClassMember[] members) {
        final MemberChooser<DescriptorClassMember> chooser = new MemberChooser<DescriptorClassMember>(members, true, true, project);
        chooser.setTitle(getChooserTitle());
        chooser.show();
        if (chooser.getExitCode() != DialogWrapper.OK_EXIT_CODE) return null;
        return chooser;
    }

    protected abstract String getChooserTitle();

    @Override
    public boolean isValidFor(Editor editor, PsiFile file) {
        if (!(file instanceof JetFile)) {
            return false;
        }
        final PsiElement elementAtCaret = file.findElementAt(editor.getCaretModel().getOffset());
        final JetClassOrObject classOrObject = PsiTreeUtil.getParentOfType(elementAtCaret, JetClassOrObject.class);
        return classOrObject != null;
    }

    @Override
    public void invoke(@NotNull final Project project, @NotNull final Editor editor, @NotNull PsiFile file) {
        final PsiElement elementAtCaret = file.findElementAt(editor.getCaretModel().getOffset());
        final JetClassOrObject classOrObject = PsiTreeUtil.getParentOfType(elementAtCaret, JetClassOrObject.class);
        Set<CallableMemberDescriptor> missingImplementations = collectMethodsToGenerate(classOrObject);
        if (missingImplementations.isEmpty()) {
            HintManager.getInstance().showErrorHint(editor, "No methods to implement have been found");
            return;
        }
        List<DescriptorClassMember> members = membersFromDescriptors(missingImplementations);
        final MemberChooser<DescriptorClassMember> chooser = showOverrideImplementChooser(project,
                                                                                          members.toArray(new DescriptorClassMember[members.size()]));
        if (chooser == null) {
            return;
        }

        final List<DescriptorClassMember> selectedElements = chooser.getSelectedElements();
        if (selectedElements == null || selectedElements.isEmpty()) return;

        new WriteCommandAction(project, file) {
          protected void run(final Result result) throws Throwable {
            generateMethods(project, editor, classOrObject, selectedElements);
          }
        }.execute();

    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
