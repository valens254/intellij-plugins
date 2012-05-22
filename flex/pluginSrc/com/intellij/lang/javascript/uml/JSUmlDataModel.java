/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *                                           
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.intellij.lang.javascript.uml;

import com.intellij.diagram.*;
import com.intellij.lang.javascript.flex.ECMAScriptImportOptimizer;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.ImportUtils;
import com.intellij.lang.javascript.flex.XmlBackedJSClassImpl;
import com.intellij.lang.javascript.flex.actions.newfile.NewFlexComponentAction;
import com.intellij.lang.javascript.index.JSPackageIndex;
import com.intellij.lang.javascript.index.JSPackageIndexInfo;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.lang.javascript.psi.ecmal4.JSPackage;
import com.intellij.lang.javascript.psi.ecmal4.JSQualifiedNamedElement;
import com.intellij.lang.javascript.psi.ecmal4.JSReferenceList;
import com.intellij.lang.javascript.psi.impl.JSPsiImplUtils;
import com.intellij.lang.javascript.psi.resolve.JSInheritanceUtil;
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil;
import com.intellij.lang.javascript.psi.util.JSUtils;
import com.intellij.lang.javascript.refactoring.FormatFixer;
import com.intellij.lang.javascript.refactoring.util.JSRefactoringUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * @author Konstantin Bulenkov
 * @author Kirill Safonov
 */
public class JSUmlDataModel extends DiagramDataModel<Object> {
  private final Map<String, SmartPsiElementPointer<JSClass>> classesAddedByUser = new HashMap<String, SmartPsiElementPointer<JSClass>>();
  private final Map<String, SmartPsiElementPointer<JSClass>> classesRemovedByUser = new HashMap<String, SmartPsiElementPointer<JSClass>>();
  private final String initialPackage;
  private SmartPsiElementPointer<? extends PsiElement> myInitialElement;
  private final Set<String> packages = new HashSet<String>();
  private final Set<String> packagesRemovedByUser = new HashSet<String>();

  private final VirtualFile myEditorFile;
  private final SmartPointerManager spManager;

  public JSUmlDataModel(final Project project, Object element, final VirtualFile file, DiagramProvider<Object> provider) {
    super(project, provider);
    myEditorFile = file;
    spManager = SmartPointerManager.getInstance(project);
    if (element instanceof JSClass) {
      initialPackage = null;
      myInitialElement = spManager.createSmartPsiElementPointer((JSClass)element);
      JSClass psiClass = (JSClass)element;
      classesAddedByUser.put(psiClass.getQualifiedName(), (SmartPsiElementPointer<JSClass>)myInitialElement);
      final Collection<JSClass> classes = JSInheritanceUtil.findAllParentsForClass(psiClass, true);
      for (JSClass aClass : classes) {
        classesAddedByUser.put(aClass.getQualifiedName(), spManager.createSmartPsiElementPointer(aClass));
      }
    }
    else if (element instanceof String) {
      initialPackage = (String)element;

      final GlobalSearchScope searchScope = GlobalSearchScope.allScope(project);
      for (String aPackage : getSubPackages(initialPackage, searchScope)) {
        packages.add(aPackage);
      }

      for (JSClass jsClass : getClasses(initialPackage, searchScope)) {
        classesAddedByUser.put(jsClass.getQualifiedName(), spManager.createSmartPsiElementPointer(jsClass));
      }
    }
    else {
      initialPackage = null;
    }
  }

  private static Collection<String> getSubPackages(final String packageName, final GlobalSearchScope searchScope) {
    final Collection<String> result = new HashSet<String>();
    JSPackageIndex.processElementsInScope(packageName, null, new JSPackageIndex.PackageElementsProcessor() {
      public boolean process(VirtualFile file, String name, JSPackageIndexInfo.Kind kind, boolean isPublic) {
        if (kind == JSPackageIndexInfo.Kind.PACKAGE) {
          result.add(StringUtil.getQualifiedName(packageName, name));
        }
        return true;
      }
    }, searchScope, searchScope.getProject());
    return result;
  }

  private static Collection<JSClass> getClasses(final String packageName, final GlobalSearchScope searchScope) {
    final Collection<JSClass> result = new HashSet<JSClass>();
    JSPackageIndex.processElementsInScope(packageName, null, new JSPackageIndex.PackageElementsProcessor() {
      public boolean process(VirtualFile file, String name, JSPackageIndexInfo.Kind kind, boolean isPublic) {
        String qualifiedName = StringUtil.getQualifiedName(packageName, name);
        if (kind == JSPackageIndexInfo.Kind.CLASS || kind == JSPackageIndexInfo.Kind.INTERFACE) {
          PsiElement element = JSResolveUtil.findClassByQName(qualifiedName, searchScope);
          if (element instanceof JSClass) {
            result.add((JSClass)element);
          }
        }
        return true;
      }
    }, searchScope, searchScope.getProject());
    return result;
  }

  private final Collection<DiagramNode<Object>> myNodes = new HashSet<DiagramNode<Object>>();
  private final Collection<DiagramEdge<Object>> myEdges = new HashSet<DiagramEdge<Object>>();
  private final Collection<DiagramEdge<Object>> myDependencyEdges = new HashSet<DiagramEdge<Object>>();

  private final Collection<DiagramNode<Object>> myNodesOld = new HashSet<DiagramNode<Object>>();
  private final Collection<DiagramEdge<Object>> myEdgesOld = new HashSet<DiagramEdge<Object>>();
  private final Collection<DiagramEdge<Object>> myDependencyEdgesOld = new HashSet<DiagramEdge<Object>>();


  @NotNull
  public Collection<DiagramNode<Object>> getNodes() {
    return new ArrayList<DiagramNode<Object>>(myNodes);
  }

  @NotNull
  public Collection<DiagramEdge<Object>> getEdges() {
    if (myDependencyEdges.isEmpty()) {
      return myEdges;
    }
    else {
      Collection<DiagramEdge<Object>> allEdges = new HashSet<DiagramEdge<Object>>(myEdges);
      allEdges.addAll(myDependencyEdges);
      return allEdges;
    }
  }

  @NotNull
  @NonNls
  public String getNodeName(final DiagramNode<Object> node) {
    Object element = getIdentifyingElement(node);
    if (element instanceof JSClass) {
      return "Class " + ((JSClass)element).getQualifiedName();
    }
    else if (element instanceof String) {
      return "Package " + element;
    }
    return "";
  }

  @Override
  public void removeNode(DiagramNode<Object> node) {
    removeElement(getIdentifyingElement(node));
  }

  @Override
  public void removeEdge(DiagramEdge<Object> edge) {
    final Object source = edge.getSource().getIdentifyingElement();
    final Object target = edge.getTarget().getIdentifyingElement();
    final DiagramRelationshipInfo relationship = edge.getRelationship();
    if (!(source instanceof JSClass) || !(target instanceof JSClass) || relationship == DiagramRelationshipInfo.NO_RELATIONSHIP) {
      return;
    }

    final JSClass fromClass = (JSClass)source;
    final JSClass toClass = (JSClass)target;

    if (JSRefactoringUtil.isInLibrary(fromClass) || JSResolveUtil.isObjectClass(toClass)) {
      return;
    }

    if (fromClass instanceof XmlBackedJSClassImpl && !toClass.isInterface()) {
      Messages.showErrorDialog(fromClass.getProject(), FlexBundle.message("base.component.needed.message"),
                               FlexBundle.message("remove.edge.title"));
      return;
    }

    if (Messages.showYesNoDialog(fromClass.getProject(),
                                 FlexBundle
                                   .message("remove.inheritance.link.prompt", fromClass.getQualifiedName(), toClass.getQualifiedName()),
                                 FlexBundle.message("remove.edge.title"),
                                 Messages.getQuestionIcon()) != Messages.YES) {
      return;
    }

    final Runnable runnable = new Runnable() {
      public void run() {
        JSReferenceList refList =
          !fromClass.isInterface() && toClass.isInterface() ? fromClass.getImplementsList() : fromClass.getExtendsList();
        List<FormatFixer> formatters = new ArrayList<FormatFixer>();
        JSRefactoringUtil.removeFromReferenceList(refList, toClass, formatters);
        if (!(fromClass instanceof XmlBackedJSClassImpl) && needsImport(fromClass, toClass)) {
          formatters.addAll(ECMAScriptImportOptimizer.executeNoFormat(fromClass.getContainingFile()));
        }
        FormatFixer.fixAll(formatters);
      }
    };

    DiagramAction
      .performCommand(getBuilder(), runnable, FlexBundle.message("remove.relationship.command.name"), null, fromClass.getContainingFile());
  }

  private static boolean needsImport(JSClass context, JSClass referenced) {
    String packageName = StringUtil.getPackageName(referenced.getQualifiedName());
    return !packageName.isEmpty() && !packageName.equals(StringUtil.getPackageName(context.getQualifiedName()));
  }

  public void refreshDataModel() {
    clearAll();
    updateDataModel();
  }

  @NotNull
  @Override
  public ModificationTracker getModificationTracker() {
    return PsiManager.getInstance(getProject()).getModificationTracker();
  }

  private void clearAll() {
    clearAndBackup(myNodes, myNodesOld);
    clearAndBackup(myEdges, myEdgesOld);
    clearAndBackup(myDependencyEdges, myDependencyEdgesOld);
  }

  public void removeAllElements() {
    classesRemovedByUser.clear();
    classesRemovedByUser.putAll(classesAddedByUser);
    classesAddedByUser.clear();
    packagesRemovedByUser.clear();
    packagesRemovedByUser.addAll(packages);
    packages.clear();
    clearAll();
  }

  private boolean isAllowedToShow(JSClass psiClass) {
    if (psiClass == null || !psiClass.isValid()) return false;

    final DiagramScopeManager<Object> scopeManager = getScopeManager();
    if (scopeManager != null && !scopeManager.contains(psiClass)) return false;

    final PsiElement initialElement = getInitialElement();
    if (isInsidePackages(psiClass)) return false;
    if (initialElement instanceof JSClass && equals(psiClass, (JSClass)initialElement)) return true;
    return true;
  }

  private static boolean equals(JSClass one, JSClass another) {
    return one != null &&
           one.isValid() &&
           another != null &&
           another.isValid() &&
           one.getQualifiedName() != null &&
           one.getQualifiedName().equals(another.getQualifiedName());
  }

  public synchronized void updateDataModel() {
    final Set<JSClass> classes = getAllClasses();
    syncPackages();
    final Set<JSClass> interfaces = new HashSet<JSClass>();

    for (String psiPackage : packages) {

      if (JSElementManager.packageExists(getProject(), psiPackage, GlobalSearchScope.allScope(getProject()))) {
        myNodes.add(new JSPackageNode(psiPackage, getProvider()));
      }
    }
    for (JSClass psiClass : classes) {
      if (isAllowedToShow(psiClass)) {
        myNodes.add(new JSClassNode(psiClass, getProvider()));
      }

      if (psiClass.isInterface()) {
        interfaces.add(psiClass);
      }
    }

    for (JSClass psiClass : classes) {
      {
        DiagramNode<Object> source = findNode(psiClass);
        DiagramNode<Object> target = null;
        Collection<JSClass> processed = new ArrayList<JSClass>();
        JSClass superClass = getSuperClass(psiClass, processed);
        while (target == null && superClass != null) {
          target = findNode(superClass);
          superClass = getSuperClass(superClass, processed);
        }

        if (source != null && target != null && source != target) {
          if (!((JSClass)getIdentifyingElement(source)).isInterface() ||
              !JSResolveUtil.isObjectClass((JSClass)getIdentifyingElement(target))) {
            addEdge(source, target,
                    psiClass.isInterface() ? FlashDiagramRelationship.INTERFACE_GENERALIZATION : FlashDiagramRelationship.GENERALIZATION);
          }
        }
      }

      for (JSClass inter : psiClass.getImplementedInterfaces()) {
        if (interfaces.contains(inter)) {
          DiagramNode<Object> source = findNode(psiClass);
          DiagramNode<Object> target = findNode(inter);
          if (source != null && target != null && source != target) {
            addEdge(source, target, FlashDiagramRelationship.REALIZATION);
          }
        }
      }
      if (psiClass.isInterface()) {
        Set<JSClass> found = new HashSet<JSClass>();
        findNearestInterfaces(psiClass, found);

        for (JSClass inter : found) {
          if (interfaces.contains(inter)) {
            DiagramNode<Object> source = findNode(psiClass);
            DiagramNode<Object> target = findNode(inter);
            if (source != null && target != null && source != target) {
              addEdge(source, target, FlashDiagramRelationship.INTERFACE_GENERALIZATION);
            }
          }
        }
      }
      else {
        //Collect all realized interfaces
        Set<JSClass> inters = new HashSet<JSClass>();
        ContainerUtil.addAll(inters, psiClass.getImplementedInterfaces());
        Collection<JSClass> processed = new ArrayList<JSClass>();
        JSClass cur = getSuperClass(psiClass, processed);
        while (cur != null) {
          if (findNode(cur) == null) {
            ContainerUtil.addAll(inters, cur.getImplementedInterfaces());
          }
          else {
            break;
          }
          cur = getSuperClass(cur, processed);
        }

        ArrayList<JSClass> faces = new ArrayList<JSClass>(inters);

        while (!faces.isEmpty()) {
          JSClass inter = faces.get(0);
          if (findNode(inter) != null) {
            DiagramNode<Object> source = findNode(psiClass);
            DiagramNode<Object> target = findNode(inter);
            if (source != null && target != null && source != target) {
              addEdge(source, target, FlashDiagramRelationship.REALIZATION);
            }
            faces.remove(inter);
          }
          else {
            faces.remove(inter);
            ContainerUtil.addAll(faces, inter.getImplementedInterfaces());
          }
        }
      }
    }

    if (isShowDependencies()) {
      final EnumSet<JSDependenciesSettingsOption> options = JSDependenciesSettingsOption.getEnabled();
      for (JSClass psiClass : classes) {
        showDependenciesFor(psiClass, options);
      }
    }
    //merge!
    mergeWithBackup(myNodes, myNodesOld);
    mergeWithBackup(myEdges, myEdgesOld);
    mergeWithBackup(myDependencyEdges, myDependencyEdgesOld);
  }

  private void showDependenciesFor(final JSClass clazz, final EnumSet<JSDependenciesSettingsOption> options) {
    DiagramNode<Object> mainNode = findNode(clazz);
    if (mainNode == null) return;

    JSUmlDependencyProvider provider = new JSUmlDependencyProvider(clazz);

    Collection<Pair<JSClass, FlashDiagramRelationship>> list = provider.computeUsedClasses();
    for (Pair<JSClass, FlashDiagramRelationship> pair : list) {
      if (shouldShow(options, clazz, pair.first, pair.second)) {
        DiagramNode<Object> node = findNode(pair.first);
        if (node != null) {
          addDependencyEdge(mainNode, node, pair.second);
        }
      }
    }
  }

  private static boolean shouldShow(EnumSet<JSDependenciesSettingsOption> options,
                                    final JSClass from,
                                    final JSClass to,
                                    final FlashDiagramRelationship relShip) {
    if (JSResolveUtil.isObjectClass(from) && JSResolveUtil.isObjectClass(to)) {
      return false;
    }
    if (!options.contains(JSDependenciesSettingsOption.SELF) && JSPsiImplUtils.isTheSameClass(from, to)) {
      return false;
    }
    if (!options.contains(JSDependenciesSettingsOption.ONE_TO_ONE) && relShip.getType() == FlashDiagramRelationship.TYPE_ONE_TO_ONE) {
      return false;
    }
    if (!options.contains(JSDependenciesSettingsOption.ONE_TO_MANY) && relShip.getType() == FlashDiagramRelationship.TYPE_ONE_TO_MANY) {
      return false;
    }
    if (!options.contains(JSDependenciesSettingsOption.USAGES) && relShip.getType() == FlashDiagramRelationship.TYPE_DEPENDENCY) {
      return false;
    }
    if (!options.contains(JSDependenciesSettingsOption.CREATE) && relShip.getType() == FlashDiagramRelationship.TYPE_CREATE) {
      return false;
    }
    return true;
  }

  @Nullable
  private static JSClass getSuperClass(JSClass psiClass, Collection<JSClass> processed) {
    JSClass[] superClasses = psiClass.getSuperClasses();
    if (superClasses.length > 0 &&
        !superClasses[0].isEquivalentTo(psiClass) &&
        !JSPsiImplUtils.containsEquivalent(processed, superClasses[0])) {
      processed.add(superClasses[0]);
      return superClasses[0];
    }
    return null;
  }

  private static <T> void clearAndBackup(Collection<T> target, Collection<T> backup) {
    backup.clear();
    backup.addAll(target);
    target.clear();
  }

  private static <T> void mergeWithBackup(Collection<T> target, Collection<T> backup) {
    for (T t : backup) {
      if (target.contains(t)) {
        target.remove(t);
        target.add(t);
      }
    }
  }

  private void syncPackages() {
    final GlobalSearchScope searchScope = GlobalSearchScope.allScope(getProject());
    if (initialPackage == null || JSElementManager.packageExists(getProject(), initialPackage, searchScope)) return;

    final Set<String> psiPackages = new HashSet<String>();
    for (String sub : getSubPackages(initialPackage, searchScope)) {
      psiPackages.add(sub);
    }
    for (String fqn : packages) psiPackages.remove(fqn);
    for (String fqn : packagesRemovedByUser) psiPackages.remove(fqn);

    if (psiPackages.size() > 0) {
      packages.addAll(psiPackages);
    }
  }

  private static void findNearestInterfaces(final JSClass psiClass, final Set<JSClass> result) {
    for (JSClass anInterface : psiClass.getSuperClasses()) {
      if (result.contains(anInterface)) {
        continue; // don't check isEquivalent, equality check is enough for interfaces
      }
      result.add(anInterface);
      findNearestInterfaces(anInterface, result);
    }
  }

  private static boolean isGeneralizationEdgeAllowed(final JSClass psiClass) {
    return !psiClass.isInterface();
  }

  private boolean isInsidePackages(JSClass psiClass) {
    return packages.contains(StringUtil.getPackageName(psiClass.getQualifiedName()));
  }

  public JSUmlEdge addEdge(DiagramNode<Object> from, DiagramNode<Object> to, DiagramRelationshipInfo relationship) {
    return addEdge(from, to, relationship, myEdges);
  }

  public JSUmlEdge addDependencyEdge(DiagramNode<Object> from, DiagramNode<Object> to, DiagramRelationshipInfo relationship) {
    return addEdge(from, to, relationship, myDependencyEdges);
  }

  private static JSUmlEdge addEdge(DiagramNode<Object> from,
                                   DiagramNode<Object> to,
                                   DiagramRelationshipInfo relationship,
                                   Collection<DiagramEdge<Object>> storage) {
    for (DiagramEdge edge : storage) {
      if (edge.getSource() == from && edge.getTarget() == to && relationship.equals(edge.getRelationship())) return null;
    }
    JSUmlEdge result = new JSUmlEdge(from, to, relationship);
    storage.add(result);
    return result;
  }

  private Set<JSClass> getAllClasses() {
    Set<JSClass> classes = new HashSet<JSClass>();
    for (SmartPsiElementPointer<JSClass> pointer : classesAddedByUser.values()) {
      classes.add(pointer.getElement());
    }
    final GlobalSearchScope searchScope = GlobalSearchScope.allScope(getProject());
    if (initialPackage != null && JSElementManager.packageExists(getProject(), initialPackage, searchScope)) {
      classes.addAll(getClasses(initialPackage, searchScope));
    }
    for (String psiPackage : packages) {
      if (JSElementManager.packageExists(getProject(), psiPackage, searchScope)) {
        classes.addAll(getClasses(psiPackage, searchScope));
      }
    }
    classes.remove(null);
    Set<JSClass> temp = new HashSet<JSClass>();

    for (JSClass aClass : classes) {
      if (!aClass.isValid()) temp.add(aClass);
    }

    for (SmartPsiElementPointer<JSClass> cls : classesRemovedByUser.values()) {
      classes.remove(cls.getElement());
    }
    classes.removeAll(temp);
    return classes;
  }

  @Nullable
  public DiagramNode<Object> findNode(Object object) {
    String objectFqn = getFqn(object);
    for (DiagramNode<Object> node : getNodes()) {
      final String fqn = getFqn(getIdentifyingElement(node));
      if (fqn != null && fqn.equals(objectFqn)) {
        if (object instanceof JSClass && !(node instanceof JSClassNode)) continue;
        if (object instanceof String && !(node instanceof JSPackageNode)) continue;
        return node;
      }
    }
    //final SmartPsiElementPointer<JSPackage> ptr = packages.get(UmlUtils.getPackageName(psiElement));
    return null; //ptr == null ? null : findNode(ptr.getElement());
  }

  @Nullable
  private static String getFqn(Object element) {
    if (element instanceof JSQualifiedNamedElement) {
      String qName = ((JSQualifiedNamedElement)element).getQualifiedName();
      return qName != null ? JSVfsResolver.fixVectorTypeName(qName) : null;
    }
    if (element instanceof String) {
      return (String)element;
    }
    return null;
  }

  public boolean contains(PsiElement psiElement) {
    return findNode(psiElement) != null;
  }

  public void dispose() {
  }

  public void removeElement(final Object element) {
    DiagramNode node = findNode(element);
    if (node == null) {
      classesAddedByUser.remove(getFqn(element));
      return;
    }

    Collection<DiagramEdge> edgesToRemove = new ArrayList<DiagramEdge>();
    for (DiagramEdge edge : myEdges) {
      if (node.equals(edge.getTarget()) || node.equals(edge.getSource())) {
        edgesToRemove.add(edge);
      }
    }
    myEdges.removeAll(edgesToRemove);

    Collection<DiagramEdge> dependencyEdgesToRemove = new ArrayList<DiagramEdge>();
    for (DiagramEdge edge : myDependencyEdges) {
      if (node.equals(edge.getTarget()) || node.equals(edge.getSource())) {
        dependencyEdgesToRemove.add(edge);
      }
    }
    myDependencyEdges.removeAll(dependencyEdgesToRemove);


    myNodes.remove(node);
    if (element instanceof JSClass) {
      final JSClass psiClass = (JSClass)element;
      classesRemovedByUser.put(psiClass.getQualifiedName(), spManager.createSmartPsiElementPointer(psiClass));
      classesAddedByUser.remove(psiClass.getQualifiedName());
    }
    if (element instanceof String) {
      String p = (String)element;
      packages.remove(p);
      packagesRemovedByUser.add(p);

      Set<String> toDelete = new HashSet<String>();
      for (String key : classesAddedByUser.keySet()) {
        final SmartPsiElementPointer<JSClass> pointer = classesAddedByUser.get(key);
        final JSClass psiClass = pointer.getElement();
        if (p.equals(StringUtil.getPackageName(psiClass.getQualifiedName()))) {
          toDelete.add(key);
        }
      }
      for (String key : toDelete) {
        classesAddedByUser.remove(key);
      }
    }
  }

  @Nullable
  public DiagramNode<Object> addElement(Object element) {
    if (findNode(element) != null) return null;

    if (element instanceof JSClass) {
      if (!isAllowedToShow((JSClass)element)) {
        return null;
      }

      JSClass psiClass = (JSClass)element;
      if (psiClass.getQualifiedName() == null) return null;
      final SmartPsiElementPointer<JSClass> pointer = spManager.createSmartPsiElementPointer(psiClass);
      final String fqn = psiClass.getQualifiedName();
      classesAddedByUser.put(fqn, pointer);
      classesRemovedByUser.remove(fqn);

      setupScopeManager(psiClass, true);

      return new JSClassNode((JSClass)element, getProvider());
    }
    else if (element instanceof String) {
      String aPackage = (String)element;
      packages.add(aPackage);
      packagesRemovedByUser.remove(aPackage);
      return new JSPackageNode(aPackage, getProvider());
    }
    return null;
  }


  @Override
  public void expandNode(final DiagramNode<Object> node) {
    final Object element = node.getIdentifyingElement();
    if (element instanceof String) {
      expandPackage((String)element);
    }
  }

  public void expandPackage(final String psiPackage) {
    packages.remove(psiPackage);
    packagesRemovedByUser.add(psiPackage);
    final GlobalSearchScope searchScope = GlobalSearchScope.allScope(getProject());
    for (JSClass psiClass : getClasses(psiPackage, searchScope)) {
      addElement(psiClass);
    }
    for (String aPackage : getSubPackages(psiPackage, searchScope)) {
      addElement(aPackage);
    }
  }

  @Override
  public void collapseNode(final DiagramNode<Object> node) {
    Object element = node.getIdentifyingElement();
    String fqn = getFqn(element);
    if (fqn == null) {
      return;
    }

    String parentPackage = StringUtil.getPackageName(fqn);
    if (parentPackage.isEmpty()) {
      return;
    }

    final String fqnStart = parentPackage + ".";
    final ArrayList<String> toRemove = new ArrayList<String>();
    for (String p : packages) {
      if (p.startsWith(fqnStart)) {
        toRemove.add(p);
      }
    }
    packages.removeAll(toRemove);
    toRemove.clear();

    for (String s : classesAddedByUser.keySet()) {
      if (s.startsWith(fqnStart)) {
        toRemove.add(s);
      }
    }
    for (String s : toRemove) {
      classesAddedByUser.remove(s);
    }
    packages.add(parentPackage);
    packagesRemovedByUser.remove(parentPackage);
  }

  List<String> getAllClassesFQN() {
    List<String> fqns = new ArrayList<String>();
    for (DiagramNode node : getNodes()) {
      final Object identifyingElement = getIdentifyingElement(node);
      if (identifyingElement instanceof JSClass) {
        fqns.add(((JSClass)identifyingElement).getQualifiedName());
      }
    }
    return fqns;
  }

  List<String> getAllPackagesFQN() {
    List<String> fqns = new ArrayList<String>();
    for (DiagramNode node : getNodes()) {
      final Object identifyingElement = getIdentifyingElement(node);
      if (identifyingElement instanceof JSPackage) {
        fqns.add(((JSPackage)identifyingElement).getQualifiedName());
      }
    }
    return fqns;
  }

  @Nullable
  public PsiElement getInitialElement() {
    if (myInitialElement == null) return null;
    final PsiElement element = myInitialElement.getElement();
    return element == null || !element.isValid() ? null : element;
  }

  public String getInitialPackage() {
    return initialPackage;
  }

  public boolean hasNotValid() {
    for (DiagramNode<Object> node : getNodes()) {
      if (!isValid(getIdentifyingElement(node))) {
        return true;
      }
    }
    return false;
  }

  private boolean isValid(Object element) {
    if (element instanceof PsiElement) return ((PsiElement)element).isValid();
    return false;
  }

  public static String getMessage(final JSClass source, final JSClass target, final DiagramRelationshipInfo relationship) {
    if (relationship == FlashDiagramRelationship.ANNOTATION) {
      return "Remove annotation from class"; //TODO: return UmlBundle.message("remove.annotation.from.class", target.getName(), source.getName());
    }
    else {
      return "This will remove relationship between classes";//TODO: return UmlBundle.message("this.will.remove.relationship.link.between.classes", source.getQualifiedName());
    }
  }

  public VirtualFile getFile() {
    return myEditorFile;
  }

  @Override
  public boolean hasElement(Object element) {
    return findNode(element) != null;
  }

  @Override
  public boolean isPsiListener() {
    return true;
  }

  @Nullable
  public static Object getIdentifyingElement(DiagramNode node) {
    if (node instanceof JSClassNode || node instanceof JSPackageNode) {
      return node.getIdentifyingElement();
    }
    if (node instanceof DiagramNoteNode) {
      final DiagramNode delegate = ((DiagramNoteNode)node).getIdentifyingElement();
      if (delegate != node) {
        return getIdentifyingElement(delegate);
      }
    }
    return null;
  }

  @Override
  @Nullable
  public DiagramEdge<Object> createEdge(@NotNull final DiagramNode<Object> from, @NotNull final DiagramNode<Object> to) {
    final JSClass fromClass = (JSClass)from.getIdentifyingElement();
    final JSClass toClass = (JSClass)to.getIdentifyingElement();

    if (fromClass.isEquivalentTo(toClass)) {
      return null;
    }

    if (toClass.isInterface()) {
      if (JSPsiImplUtils.containsEquivalent(fromClass.isInterface() ?
                                            fromClass.getSuperClasses() : fromClass.getImplementedInterfaces(), toClass)) {
        return null;
      }

      Callable<DiagramEdge<Object>> callable = new Callable<DiagramEdge<Object>>() {
        @Override
        public DiagramEdge<Object> call() throws Exception {
          String targetQName = toClass.getQualifiedName();
          JSRefactoringUtil.addToSupersList(fromClass, targetQName, true);
          if (targetQName.contains(".") && !(fromClass instanceof XmlBackedJSClassImpl)) {
            List<FormatFixer> formatters = new ArrayList<FormatFixer>();
            formatters.add(ImportUtils.insertImportStatements(fromClass, Collections.singletonList(targetQName)));
            formatters.addAll(ECMAScriptImportOptimizer.executeNoFormat(fromClass.getContainingFile()));
            FormatFixer.fixAll(formatters);
          }
          return addEdgeAndRefresh(from, to, fromClass.isInterface()
                                             ? FlashDiagramRelationship.GENERALIZATION
                                             : FlashDiagramRelationship.INTERFACE_GENERALIZATION);
        }
      };
      String commandName =
        FlexBundle
          .message(fromClass.isInterface() ? "create.extends.relationship.command.name" : "create.implements.relationship.command.name",
                   fromClass.getQualifiedName(), toClass.getQualifiedName());
      return DiagramAction.performCommand(getBuilder(), callable, commandName, null, fromClass.getContainingFile());
    }
    else {
      if (fromClass.isInterface()) {
        return null;
      }
      else if (fromClass instanceof XmlBackedJSClassImpl) {
        JSClass[] superClasses = fromClass.getSuperClasses();
        if (JSPsiImplUtils.containsEquivalent(superClasses, toClass)) {
          return null;
        }

        if (superClasses.length > 0) { // if base component is not resolved, replace it silently
          final JSClass currentParent = superClasses[0];
          if (Messages.showYesNoDialog(
            FlexBundle.message("replace.base.component.prompt", currentParent.getQualifiedName(), toClass.getQualifiedName()),
            FlexBundle.message("create.edge.title"),
            Messages.getQuestionIcon()) == Messages.NO) {
            return null;
          }
        }
        Callable<DiagramEdge<Object>> callable = new Callable<DiagramEdge<Object>>() {
          @Override
          public DiagramEdge<Object> call() throws Exception {
            Pair<String, String> prefixAndNamespace =
              NewFlexComponentAction.getPrefixAndNamespace(((XmlBackedJSClassImpl)fromClass).getParent(),
                                                           toClass.getQualifiedName());
            ((XmlBackedJSClassImpl)fromClass)
              .setBaseComponent(toClass.getQualifiedName(), prefixAndNamespace.first, prefixAndNamespace.second);
            return addEdgeAndRefresh(from, to, DiagramRelationships.GENERALIZATION);
          }
        };
        String commandName =
          FlexBundle.message("create.extends.relationship.command.name", fromClass.getQualifiedName(), toClass.getQualifiedName());
        return DiagramAction.performCommand(getBuilder(), callable, commandName, null, fromClass.getContainingFile());
      }
      else {
        final JSClass[] superClasses = fromClass.getSuperClasses();
        if (JSPsiImplUtils.containsEquivalent(superClasses, toClass)) {
          return null;
        }

        if (superClasses.length > 0 &&
            !JSResolveUtil.isObjectClass(superClasses[0])) { // if base class is not resolved, replace it silently
          final JSClass currentParent = superClasses[0];
          if (Messages.showYesNoDialog(
            FlexBundle.message("replace.base.class.prompt", currentParent.getQualifiedName(), toClass.getQualifiedName()),
            FlexBundle.message("create.edge.title"),
            Messages.getQuestionIcon()) == Messages.NO) {
            return null;
          }
        }
        Callable<DiagramEdge<Object>> callable = new Callable<DiagramEdge<Object>>() {
          @Override
          public DiagramEdge<Object> call() throws Exception {
            List<FormatFixer> formatters = new ArrayList<FormatFixer>();
            boolean optimize = false;
            if (superClasses.length > 0 && !JSResolveUtil.isObjectClass(superClasses[0])) {
              JSRefactoringUtil.removeFromReferenceList(fromClass.getExtendsList(), superClasses[0], formatters);
              optimize = needsImport(fromClass, superClasses[0]);
            }
            JSRefactoringUtil.addToSupersList(fromClass, toClass.getQualifiedName(), false);
            if (needsImport(fromClass, toClass)) {
              formatters.add(ImportUtils.insertImportStatements(fromClass, Collections.singletonList(toClass.getQualifiedName())));
              optimize = true;
            }
            if (optimize) {
              formatters.addAll(ECMAScriptImportOptimizer.executeNoFormat(fromClass.getContainingFile()));
            }
            FormatFixer.fixAll(formatters);
            return addEdgeAndRefresh(from, to, DiagramRelationships.GENERALIZATION);
          }
        };
        String commandName =
          FlexBundle.message("create.extends.relationship.command.name", fromClass.getQualifiedName(), toClass.getQualifiedName());
        return DiagramAction.performCommand(getBuilder(), callable, commandName, null, fromClass.getContainingFile());
      }
    }
  }

  private DiagramEdge<Object> addEdgeAndRefresh(DiagramNode<Object> from, DiagramNode<Object> to, DiagramRelationshipInfo type) {
    JSUmlEdge result = addEdge(from, to, type);
    final DiagramBuilder builder = getBuilder();
    if (builder != null) {
      builder.update(true, false);
    }
    return result;
  }

  @Override
  public boolean isDependencyDiagramSupported() {
    return true;
  }
}
