import localUnitTest from './LocalUnit.test';
import coreLogicTest from './CoreLogic.test';
import apiAuthTest from './ApiAuth.test';
import screenStackTest from './ScreenStack.test';

export default function testsuite() {
  localUnitTest();
  coreLogicTest();
  apiAuthTest();
  // UI 导航冒烟:覆盖"下一关点不动 / 最后一关死按钮"这类真机上才发现的问题
  screenStackTest();
}
