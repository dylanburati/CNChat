import 'regenerator-runtime/runtime';
import { DHKeyPair, CipherStore, base64encodebytes, generateKeyWrapper } from './ChatUtils';

window.DHKeyPair = DHKeyPair;
window.CipherStore = CipherStore;
window.base64encodebytes = base64encodebytes;
window.generateKeyWrapper = generateKeyWrapper;