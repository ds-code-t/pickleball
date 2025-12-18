//package tools.dscode.common.treeparsing.parsedComponents;
//
//import org.openqa.selenium.WebDriver;
//import tools.dscode.common.seleniumextensions.ElementWrapper;
//import tools.dscode.common.treeparsing.MatchNode;
//
//public abstract class Component {
//    public final int position;
//    public String name;
//    public PhraseData parentPhrase;
//
//    public Component(MatchNode matchNode) {
//        this.name = matchNode.name();
//        this.position = matchNode.position;
//    }
//
//    public Object getValue() {
//        if (this instanceof ElementMatch elementMatch1) {
//            if(elementMatch1.wrappedElements.isEmpty())
//                return null;
//            ElementWrapper elementWrapper = elementMatch1.wrappedElements.getFirst();
//            return elementWrapper.getElementReturnValue();
//        } else {
//            return ((ValueMatch) this).value;
//        }
//    }
//}