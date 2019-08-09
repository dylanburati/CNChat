/* global window */
import 'regenerator-runtime/runtime';
import { generateKeyWrapper, base64decodebytes } from './ChatUtils';
import { ChatSessionLoader } from './ChatClient';

window.ChatSessionLoader = ChatSessionLoader;
window.generateKeyWrapper = generateKeyWrapper;
window.base64decodebytes = base64decodebytes;